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
        versionCode = 43
        versionName = "5.4"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
