import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes from a properties file kept outside the repository (see the README).
// Without it the release build is simply unsigned, so CI and anyone else can still build.
val signingFile = file(System.getenv("HUBREMOTE_SIGNING") ?: "${System.getProperty("user.home")}/.hub-remote-signing/signing.properties")
val signing = Properties().apply { if (signingFile.exists()) signingFile.inputStream().use { load(it) } }
// A signing file that is there but unusable must not break every build with an obscure error, and must
// never quietly produce an unsigned release (friends couldn't update over it): say what is wrong, and
// stop only when a release is actually being built.
val signingProblem: String? = when {
    !signingFile.exists() -> null   // nothing set up: an unsigned release, which is what CI's pull-request builds make
    listOf("storeFile", "storePassword", "keyAlias", "keyPassword").any { signing.getProperty(it).isNullOrBlank() } ->
        "$signingFile must define storeFile, storePassword, keyAlias and keyPassword."
    !file(signing.getProperty("storeFile")).exists() -> "The keystore ${signing.getProperty("storeFile")} (from $signingFile) does not exist."
    else -> null
}
if (signingProblem != null) {
    if (gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }) throw GradleException(signingProblem)
    logger.warn("Release signing is not usable: $signingProblem")
}

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
        // The version comes from the release tag: the GitHub workflow passes -PversionName=1.2.3
        // -PversionCode=10203 (major*10000 + minor*100 + patch). These defaults are for local
        // builds only. A build with the default code 1 can't update anyone's existing install, so
        // never hand one out; pass both properties if you build a release by hand.
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = project.findProperty("versionName") as String? ?: "0.0.0-local"
    }

    signingConfigs {
        if (signingFile.exists() && signingProblem == null) {
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
