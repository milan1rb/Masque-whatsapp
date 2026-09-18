plugins {
    id("com.android.application") version "8.5.2"
}

android {
    namespace = "com.perso.wamasque"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.perso.wamasque"
        minSdk = 26
        targetSdk = 34
        versionCode = 34
        versionName = "4.5"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
