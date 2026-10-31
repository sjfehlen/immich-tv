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
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Signed with the local debug keystore (~/.android/debug.keystore), outside the repo.
            signingConfig = signingConfigs.getByName("debug")
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
