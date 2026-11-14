import java.security.MessageDigest
import java.util.HexFormat
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
// SOURCE_DATE_EPOCH allows rebuilding an older release with its original UTC date.
val buildInstant=providers.environmentVariable("SOURCE_DATE_EPOCH").orNull?.let {Instant.ofEpochSecond(it.toLong())} ?: Instant.now()
val dateVersion=DateTimeFormatter.ofPattern("yyyy.MM.dd").withZone(ZoneOffset.UTC).format(buildInstant)
fun gitOutput(vararg args: String): String = runCatching {
    providers.exec {
        workingDir(rootProject.projectDir)
        commandLine("git",*args)
        isIgnoreExitValue=true
    }.standardOutput.asText.get().trim()
}.getOrDefault("")
val revision=gitOutput("rev-parse","--short=10","HEAD").takeIf {it.matches(Regex("[0-9a-f]{7,40}"))} ?: "unknown"
val modified=gitOutput("status","--porcelain","--untracked-files=no").isNotBlank()

android {
    namespace = "dev.shulhi.rex"
    ndkVersion = "30.0.16248370"
    compileSdk { version = release(37) }
    defaultConfig {
        applicationId = "dev.shulhi.rex"
        minSdk = 28
        targetSdk = 37
        versionCode = 126
        versionName = dateVersion
        buildConfigField("String","GIT_REVISION","\"$revision\"")
        buildConfigField("boolean","LOCAL_CHANGES",modified.toString())
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { jniLibs { useLegacyPackaging = false } }
    buildTypes {
        getByName("debug") {
            // A disposable package for on-device rendering tests; never touches the installed app's settings.
            if(providers.gradleProperty("renderBench").orNull=="true") applicationIdSuffix=".renderbench"
        }
        release {
            // Preserve the installed prototype identity for local release upgrades.
            signingConfig=signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("optimized") {
            initWith(getByName("release"))
            signingConfig=signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
        create("profile") {
            initWith(getByName("optimized"))
            isMinifyEnabled=false
            isShrinkResources=false
        }
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.08.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("dev.chrisbanes.haze:haze:1.5.4")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.08.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20250517")
}
val buildRust by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("python3", "scripts/build_native.py")
    inputs.files(rootProject.fileTree("../rexling-core") { exclude("**/target/**", "**/.build/**") })
    inputs.files(rootProject.fileTree("../rexling-network") { include("*.go", "go.mod", "go.sum") })
    inputs.file(rootProject.file("../rexling-core/scripts/build_native.py"))
    inputs.file(rootProject.file("scripts/build_native.py"))
    outputs.dir(project.file("src/main/jniLibs/arm64-v8a"))
}
tasks.named("preBuild").configure { dependsOn(buildRust) }

// Use the same configuration values as About, never the checkout at copy time.
tasks.register("shareRelease") {
    group="distribution"
    description="Build a release APK named with its version, build code and source revision."
    dependsOn("assembleRelease")
    val name="Rexling-$dateVersion-build${android.defaultConfig.versionCode}-$revision${if(modified) "-modified" else ""}-arm64-release.apk"
    val source=layout.buildDirectory.file("outputs/apk/release/app-release.apk")
    val target=rootProject.layout.projectDirectory.file("dist/$name")
    val checksum=rootProject.layout.projectDirectory.file("dist/$name.sha256")
    inputs.file(source)
    outputs.files(target,checksum)
    doLast {
        val apk=target.asFile
        apk.parentFile.mkdirs()
        source.get().asFile.copyTo(apk,overwrite=true)
        val hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(apk.readBytes()))
        checksum.asFile.writeText("$hash  $name\n")
        logger.lifecycle("Shareable APK: ${apk.absolutePath}")
    }
}
