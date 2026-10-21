import java.util.Properties

plugins {
    id("com.android.application")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val sdkDir: String = localProps.getProperty("sdk.dir")
    ?: providers.environmentVariable("ANDROID_HOME").orNull
    ?: error("set sdk.dir in local.properties or export ANDROID_HOME")
val ndkVersion = "30.0.16248370"

// Release signing via env (CI); falls back to the debug key when not configured.
val releaseKeystore = System.getenv("KEYSTORE_FILE")?.takeIf { file(it).exists() }
val releaseStorePassword = System.getenv("KEYSTORE_PASSWORD")
val hasReleaseSigning = releaseKeystore != null && releaseStorePassword != null
val rustDir = rootProject.projectDir.resolve("rust")
val jniLibsDir = projectDir.resolve("src/main/jniLibs")

android {
    namespace = "com.lxl66566.echo"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lxl66566.echo"
        minSdk = 30
        targetSdk = 36
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "0.1.0"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystore!!)
                storePassword = releaseStorePassword
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD") ?: releaseStorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

// Cross-compile the Rust cdylib for all ABIs and drop it into jniLibs.
// Requires cargo and cargo-ndk on PATH; ANDROID_NDK_HOME is derived from local.properties.
val buildRust by tasks.registering(Exec::class) {
    workingDir = rustDir
    environment("ANDROID_NDK_HOME", file("$sdkDir/ndk/$ndkVersion").absolutePath)
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a", "-t", "armeabi-v7a", "-t", "x86_64",
        "-o", jniLibsDir.absolutePath,
        "build", "--release", "--locked",
    )
    inputs.dir(rustDir.resolve("src"))
    inputs.file(rustDir.resolve("Cargo.toml"))
    inputs.file(rustDir.resolve("Cargo.lock"))
    outputs.dir(jniLibsDir)
}

tasks.named("preBuild") { dependsOn(buildRust) }
