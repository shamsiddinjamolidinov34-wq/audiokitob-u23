plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "uz.audiokitob"
    compileSdk = 34

    defaultConfig {
        applicationId = "uz.audiokitob"
        minSdk = 21
        targetSdk = 34
        versionCode = 3
        versionName = "1.1.1"
    }

    // Barqaror imzo: yangilanganda ilova ustiga oʻrnatilaveradi (standart "debug" kalit)
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storeType = "pkcs12"
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }

    packaging {
        resources {
            excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES", "META-INF/INDEX.LIST")
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")           // 1.15+ AGP 8.6+ va compileSdk 35 talab qiladi
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")   // PDF dan matn olish
    implementation("de.sciss:jump3r:1.0.5")                    // MP3 kodlash (sof Java, LAME porti)
}
