plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.rebound"
    compileSdk = 34

    defaultConfig {
        // Decides the external data directory songs are installed into:
        // Android/data/com.digiwb.rebound/files/songs. The Kotlin package stays
        // dev.rebound; the two are allowed to differ and renaming every source
        // file would buy nothing.
        applicationId = "com.digiwb.rebound"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                // Oboe's prefab package is built against the shared STL, and the
                // NDK refuses to mix that with the default static one.
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
        ndk {
            // 32-bit ARM is still worth carrying for older test devices.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Oboe ships its headers and prebuilt libs as a prefab AAR.
    buildFeatures {
        prefab = true
    }

    // MediaExtractor needs a real file offset into the APK, which it only gets if
    // the asset was stored uncompressed.
    androidResources {
        noCompress += listOf("wav", "ogg", "rbc", "reb")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    implementation(project(":core"))
    implementation(libs.oboe)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity)
}
