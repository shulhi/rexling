#!/usr/bin/env python3
"""Inspect APK packaging/signature/ELF alignment without installing or running it."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
MACHINES = {"arm64-v8a": "AArch64", "x86_64": "X86-64"}


def main():
    sdk = Path(os.environ.get("ANDROID_HOME") or Path.home() / "Library/Android/sdk")
    build_tools = sdk / "build-tools/36.0.0"
    ndk = sdk / "ndk/30.0.16248370"
    host = "darwin-x86_64" if os.uname().sysname == "Darwin" else "linux-x86_64"
    tools = ndk / "toolchains/llvm/prebuilt" / host / "bin"
    parser=argparse.ArgumentParser();parser.add_argument("--apk",default="app/build/outputs/apk/debug/app-debug.apk");parser.add_argument("--require-release",action="store_true")
    args=parser.parse_args()
    apk=ROOT / args.apk
    env = os.environ.copy()
    studio = Path("/Applications/Android Studio.app/Contents/jbr/Contents/Home")
    if studio.exists(): env["JAVA_HOME"] = str(studio)

    def run(args):
        return subprocess.run([str(a) for a in args], check=True, capture_output=True, text=True, env=env).stdout

    run([build_tools / "apksigner", "verify", "--verbose", apk])
    run([build_tools / "zipalign", "-c", "-P", "16", "-v", "4", apk])
    badging = run([build_tools / "aapt", "dump", "badging", apk])
    version_name = re.search(r"(?m)^package: .*versionName='([^']*)'", badging).group(1)
    version_code = int(re.search(r"(?m)^package: .*versionCode='([0-9]+)'", badging).group(1))
    label = re.search(r"(?m)^application-label:'([^']*)'$", badging).group(1)
    assert label == "Rexling", label
    expected = {f"Java_dev_shulhi_rex_NativeBridge_{name}" for name in ["create", "command", "poll", "awaitUpdate", "readUpdate", "destroy", "uploadCreate", "uploadPoll", "uploadDestroy", "forwardCreate", "forwardPoll", "forwardDestroy", "networkCommand"]}
    libraries = []
    profiles = []
    with zipfile.ZipFile(apk) as archive, tempfile.TemporaryDirectory(prefix="rex-apk-") as temporary:
        for name in archive.namelist():
            if name.startswith("assets/dexopt/"):profiles.append({"name":name,"bytes":archive.getinfo(name).file_size})
            if not name.startswith("lib/") or not name.endswith(".so"): continue
            abi = name.split("/")[1]
            assert abi in MACHINES, name
            path = Path(temporary) / abi / Path(name).name
            path.parent.mkdir(exist_ok=True)
            path.write_bytes(archive.read(name))
            assert MACHINES[abi] in run([tools / "llvm-readelf", "-h", path]), name
            headers = run([tools / "llvm-readelf", "-lW", path])
            alignments = [int(line.split()[-1], 16) for line in headers.splitlines() if line.strip().startswith("LOAD ")]
            assert alignments and all(value >= 16384 for value in alignments), (name, alignments)
            if name.endswith("librex_android.so"):
                symbols = run([tools / "llvm-nm", "-D", "--defined-only", path])
                assert all(symbol in symbols for symbol in expected), "missing JNI export"
            assert archive.getinfo(name).compress_type == zipfile.ZIP_STORED, name
            libraries.append({"name": name, "bytes": path.stat().st_size, "load_alignment": min(alignments)})
    abis = {item["name"].split("/")[1] for item in libraries}
    assert "arm64-v8a" in abis, abis
    for abi in abis:
        for library in ["librex_android.so", "libc++_shared.so", "librex_network.so"]:
            assert f"lib/{abi}/{library}" in {item["name"] for item in libraries}, (abi, library)
    manifest = run([sdk / "cmdline-tools/latest/bin/apkanalyzer", "manifest", "print", apk])
    assert 'package="dev.shulhi.rex"' in manifest
    debuggable='android:debuggable="true"' in manifest
    if args.require_release:
        assert not debuggable,"Release must not be debuggable"
        assert any(item["name"]=="assets/dexopt/baseline.prof" and item["bytes"]>0 for item in profiles),"Missing baseline profile"
    permissions = re.findall(r'<uses-permission[^>]*android:name="([^"]+)"', manifest)
    assert "android.permission.INTERNET" in permissions
    assert "android.permission.ACCESS_FINE_LOCATION" not in permissions
    print(json.dumps({"apk": str(apk.relative_to(ROOT)), "application_label": label, "version_name": version_name, "version_code": version_code, "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(), "bytes": apk.stat().st_size, "signature_verified": True, "debuggable": debuggable, "art_profiles": profiles, "zip_alignment_16k": True, "permissions": permissions, "native_libraries": libraries, "runtime_execution": "not performed by this static APK check"}, indent=2))


if __name__ == "__main__":
    main()
