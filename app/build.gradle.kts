import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes from a properties file kept outside the repository (see the README).
// Without it the release build is simply unsigned, so CI and anyone else can still build.
val signingFile = file(System.getenv("HUBREMOTE_SIGNING") ?: "${System.getProperty("user.home")}/.hub-remote-signing/signing.properties")
val signing = Properties().apply { if (signingFile.exists()) signingFile.inputStream().use { load(it) } }

android {
    namespace = "com.harmonyhub.remote"
    compileSdk = 34

    defaultConfig {
        // The app's identity on a phone: changing it later makes a different app (installs
        // side by side, settings not carried over). The code's package stays com.harmonyhub.remote.
        applicationId = "io.github.mpdeglau.hubremote"
        // Android 8.0+. Lower this if your phone is older than that.
        minSdk = 26
        targetSdk = 34
        // Raise versionCode with every build you give to anyone: Android only installs an update
        // over an existing copy when it is higher.
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (signing.isNotEmpty()) {
            create("release") {
                storeFile = file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // A different id, so a debug build and the release build can be on one phone
            // (they are signed with different keys and could not replace each other).
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.1")

    testImplementation("junit:junit:4.13.2")
    // Android bundles org.json; plain JVM unit tests need the real thing.
    testImplementation("org.json:json:20240303")
}
