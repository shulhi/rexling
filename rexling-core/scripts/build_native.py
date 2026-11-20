#!/usr/bin/env python3
"""Build the pinned Ghostty + Rust JNI library; no adb/device operations."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT.parent / "rexling-android"
# Android ABI -> Rust/NDK triple, Go architecture and build-directory suffix.
ABIS = {
    "arm64-v8a": {"triple": "aarch64-linux-android", "goarch": "arm64", "suffix": "arm64"},
    "x86_64": {"triple": "x86_64-linux-android", "goarch": "amd64", "suffix": "x86_64"},
}


def run(command, **kwargs):
    subprocess.run([str(v) for v in command], check=True, **kwargs)


def download(url, expected, path):
    if path.exists() and hashlib.sha256(path.read_bytes()).hexdigest() == expected:
        return
    temporary = path.with_suffix(path.suffix + ".part")
    run(["curl", "-fL", "--retry", "2", url, "-o", temporary])
    if hashlib.sha256(temporary.read_bytes()).hexdigest() != expected:
        raise RuntimeError(f"Checksum mismatch: {url}")
    temporary.replace(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host-test", action="store_true")
    parser.add_argument("--abi", action="append", choices=sorted(ABIS), help="Android ABI to build (repeatable; default arm64-v8a)")
    args = parser.parse_args()
    manifest = json.loads((ROOT / "native/ghostty-source.json").read_text())
    arch = {"arm64": "aarch64", "aarch64": "aarch64", "x86_64": "x86_64"}[platform.machine()]
    host = {"Darwin": "macos", "Linux": "linux"}[platform.system()]
    cache = ROOT / ".build/cache"
    cache.mkdir(parents=True, exist_ok=True)
    tool = manifest["zig"][f"{arch}-{host}"]
    zig_archive = cache / Path(tool["tarball"]).name
    source_archive = cache / "ghostty-source.tar.gz"
    # Optional cache seed within this repository; no sibling checkout is needed.
    old_cache = ROOT / "target/ghostty-build"
    for destination, old_name in [(zig_archive, zig_archive.name), (source_archive, "ghostty-source.tar.gz")]:
        if not destination.exists() and (old_cache / old_name).exists():
            shutil.copyfile(old_cache / old_name, destination)
    download(tool["tarball"], tool["shasum"], zig_archive)
    download(manifest["source_url"], manifest["source_sha256"], source_archive)
    zig_root = cache / zig_archive.name.removesuffix(".tar.xz")
    source_root = cache / ("ghostty-" + manifest["revision"])
    for archive, extracted in [(zig_archive, zig_root), (source_archive, source_root)]:
        if not extracted.exists():
            with tarfile.open(archive) as tar:
                # macOS may provide Python 3.9, before tarfile's data filter.
                for member in tar.getmembers():
                    (cache / member.name).resolve().relative_to(cache.resolve())
                    if member.issym() or member.islnk():
                        parent = (cache / member.name).parent if member.issym() else cache
                        (parent / member.linkname).resolve().relative_to(cache.resolve())
                    if not (member.isfile() or member.isdir() or member.issym() or member.islnk()):
                        raise RuntimeError("Unsupported archive member")
                tar.extractall(cache)
    env = os.environ.copy()
    cargo = shutil.which("cargo") or str(Path.home() / ".cargo/bin/cargo")
    rustup = shutil.which("rustup") or str(Path.home() / ".cargo/bin/rustup")
    env["CARGO_TARGET_DIR"] = str(ROOT / ".build/rust")
    zig_build = [zig_root / "zig", "build", "-Demit-lib-vt", "-Demit-macos-app=false", "-Demit-xcframework=false", "-Doptimize=ReleaseFast"]
    if args.host_test:
        prefix = ROOT / ".build/ghostty-host"
        run(zig_build + ["--prefix", prefix], cwd=source_root, env=env)
        env["GHOSTTY_PREFIX"] = str(prefix)
        run([cargo, "test", "--locked", "--workspace", "--all-features", "--manifest-path", ROOT / "Cargo.toml"], cwd=ROOT, env=env)
        return
    sdk = Path(env.get("ANDROID_HOME") or env.get("ANDROID_SDK_ROOT") or Path.home() / "Library/Android/sdk")
    ndk = Path(env["ANDROID_NDK_HOME"]) if env.get("ANDROID_NDK_HOME") else sdk / "ndk" / manifest["android_ndk_version"]
    env["ANDROID_HOME"] = str(sdk)
    env["ANDROID_NDK_HOME"] = str(ndk)
    compiler_root = ndk / "toolchains/llvm/prebuilt" / ("darwin-x86_64" if host == "macos" else "linux-x86_64")
    for abi in dict.fromkeys(args.abi or ["arm64-v8a"]):
        build_abi(abi, ABIS[abi], env.copy(), compiler_root, zig_build, source_root, cargo, rustup)


def build_abi(abi, spec, env, compiler_root, zig_build, source_root, cargo, rustup):
    triple, suffix = spec["triple"], spec["suffix"]
    key = triple.replace("-", "_")
    compiler = compiler_root / f"bin/{triple}28-clang"
    env[f"CC_{key}"] = str(compiler)
    env[f"CXX_{key}"] = str(compiler) + "++"
    env[f"AR_{key}"] = str(compiler_root / "bin/llvm-ar")
    env[f"CARGO_TARGET_{key.upper()}_LINKER"] = str(compiler)
    env[f"CARGO_TARGET_{key.upper()}_RUSTFLAGS"] = "-C link-arg=-Wl,-z,max-page-size=16384"
    network_prefix = ROOT / f".build/network-{suffix}"
    network_prefix.mkdir(parents=True, exist_ok=True)
    network_env = env.copy()
    network_env.update({"GOOS": "android", "GOARCH": spec["goarch"], "CGO_ENABLED": "1", "CC": str(compiler), "CGO_LDFLAGS": "-Wl,-z,max-page-size=16384"})
    run(["go", "build", "-mod=readonly", "-trimpath", "-buildmode=c-shared", "-ldflags=-s -w", "-o", network_prefix / "librex_network.so", "."], cwd=ROOT.parent / "rexling-network", env=network_env)
    env["REXLING_NETWORK_PREFIX"] = str(network_prefix)
    prefix = ROOT / f".build/ghostty-{suffix}"
    run(zig_build + ["--prefix", prefix, f"-Dtarget={triple}.28"], cwd=source_root, env=env)
    env["GHOSTTY_PREFIX"] = str(prefix)
    run([rustup, "target", "add", triple], env=env)
    run([cargo, "build", "--locked", "--release", "--target", triple, "--manifest-path", ROOT / "Cargo.toml", "-p", "rexling-jni"], cwd=ROOT, env=env)
    destination = ANDROID / "app/src/main/jniLibs" / abi
    destination.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(ROOT / f".build/rust/{triple}/release/librex_android.so", destination / "librex_android.so")
    shutil.copyfile(network_prefix / "librex_network.so", destination / "librex_network.so")
    shutil.copyfile(compiler_root / f"sysroot/usr/lib/{triple}/libc++_shared.so", destination / "libc++_shared.so")
    print(f"Android {abi} JNI libraries built in {destination}")

if __name__ == "__main__":
    main()
