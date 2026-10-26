plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.volfloat"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.volfloat"
        minSdk = 26
        targetSdk = 34
        // 每次 GitHub 构建自动递增，保证可以覆盖安装
        versionCode = 100 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1)
        versionName = "2.2"
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.jks")
            storePassword = "volfloat"
            keyAlias = "vf"
            keyPassword = "volfloat"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
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
