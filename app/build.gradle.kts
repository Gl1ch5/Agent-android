// Android application. All agent logic lives in :core; this module is UI, settings and wiring.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI passes these so every nightly installs as an upgrade of the previous one.
val ciVersionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
val ciVersionName = System.getenv("VERSION_NAME") ?: "0.1.0-dev"

android {
    namespace = "dev.agent.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.agent.app"
        minSdk = 26
        targetSdk = 35
        versionCode = ciVersionCode
        versionName = ciVersionName
    }

    // Release signing: the keystore from CI secrets when present, otherwise the
    // committed fallback key. A fixed key is what lets each nightly install over the last.
    // The fallback is public in this repo, so it only protects upgrades for sideloaded builds.
    signingConfigs {
        create("nightly") {
            val path = System.getenv("KEYSTORE_PATH")
            if (path != null) {
                storeFile = file(path)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            } else {
                storeFile = rootProject.file("signing/nightly-fallback.jks")
                storePassword = "agent-nightly"
                keyAlias = "agent-nightly"
                keyPassword = "agent-nightly"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("nightly")
        }
        debug {
            applicationIdSuffix = ".debug"
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

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.activity:activity-ktx:1.9.3")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
