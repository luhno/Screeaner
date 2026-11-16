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
        versionCode = 8
        versionName = "0.7.1"
    }

    // Updates only install over an app signed with the same key, so releases
    // must always use the same keystore. CI passes it through these variables
    // (see .github/workflows/release.yml); locally, export them before building.
    val keystore = System.getenv("SCREEANER_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = keystore
                storePassword = System.getenv("SCREEANER_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SCREEANER_KEY_ALIAS")
                keyPassword = System.getenv("SCREEANER_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Without the release key, fall back to the debug key so the build still works.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
