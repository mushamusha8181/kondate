plugins {
    id("com.android.application")
}

android {
    namespace = "jp.mushamusha.kyounokondate"
    compileSdk = 37

    defaultConfig {
        applicationId = "jp.mushamusha.kyounokondate"
        minSdk = 26
        targetSdk = 37
        versionCode = 4
        versionName = "1.3.0"
    }

    buildFeatures { buildConfig = false }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

apply(from = uri("https://raw.githubusercontent.com/mushamusha8181/light/main/design-system/mushin-glass.gradle"))
