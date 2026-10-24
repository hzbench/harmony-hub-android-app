plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.harmonyhub.remote"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.harmonyhub.remote"
        // Android 8.0+. Lower this if your phone is older than that.
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
