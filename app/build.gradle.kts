plugins {
    alias(libs.plugins.android.application)
}

// No server URL, album or API key is compiled into the app. They are entered on the
// TV at runtime via the QR-code setup screen and kept in app-private storage.

android {
    namespace = "com.fehlenfusion.immichscreensaver"
    compileSdk = 35 // compileSdk does not change runtime behaviour; targetSdk is 34

    defaultConfig {
        applicationId = "com.fehlenfusion.immichscreensaver"
        minSdk = 21
        targetSdk = 34
        // Release builds on GitHub set these from the tag (v1.2.0 -> 1.2.0); local builds keep 1.
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.0"
    }

    // GitHub release builds are signed with a key kept in the repo's Actions secrets, so every
    // published APK has the same signature and installs over the previous one.
    val releaseKeystore = System.getenv("RELEASE_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Local builds: the debug keystore (~/.android/debug.keystore), outside the repo.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// QR test images are written here so they can be checked with a real decoder (see README).
val qrDumpDir = layout.buildDirectory.dir("qr-dump").get().asFile.absolutePath
tasks.withType<Test>().configureEach {
    systemProperty("qrDumpDir", qrDumpDir)
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.org.json) // real org.json for JVM tests (android.jar only has stubs)
}
