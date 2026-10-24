plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lighthousenorth.clientandroid.example"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lighthousenorth.clientandroid.example"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    // O SDK sob teste — exatamente o que um app cliente consumiria.
    implementation(project(":client"))
}
