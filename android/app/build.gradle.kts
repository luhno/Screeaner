plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.screeaner"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.screeaner"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
    }

    // Release signing comes from the environment (see docs/DISTRIBUTION.md):
    // SCREEANER_KEYSTORE (path), SCREEANER_KEYSTORE_PASSWORD, SCREEANER_KEY_ALIAS,
    // SCREEANER_KEY_PASSWORD. Without them the release build is signed with the
    // debug key, so the APK is always installable.
    val releaseKeystore = System.getenv("SCREEANER_KEYSTORE")?.takeIf { it.isNotBlank() }

    signingConfigs {
        create("release") {
            if (releaseKeystore != null) {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("SCREEANER_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SCREEANER_KEY_ALIAS")
                keyPassword = System.getenv("SCREEANER_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (releaseKeystore != null) {
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

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
