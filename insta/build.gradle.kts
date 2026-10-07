plugins {
    id("com.android.application")
}

android {
    namespace = "com.perso.instamasque"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.perso.instamasque"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "1.7"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
