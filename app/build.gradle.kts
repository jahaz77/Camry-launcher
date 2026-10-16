plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.camry.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.camry.launcher"
        minSdk = 26
        // 29 = acceso sencillo a la carpeta /CamryLauncher/temas en Android 11
        targetSdk = 29
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    signingConfigs {
        create("camry") {
            storeFile = file("camry.keystore")
            storePassword = "camry2008"
            keyAlias = "camry"
            keyPassword = "camry2008"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("camry")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("camry")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("org.osmdroid:osmdroid-android:6.1.18")
}
