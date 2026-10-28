plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.tubeoffline.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.tubeoffline.app"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        ndk {
            // Pixel 8 is arm64-only. Add "x86_64" here if you want to run it on an emulator.
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            // yt-dlp/python are loaded natively; keep things simple and unshrunk.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            // Required by youtubedl-android: the bundled python/ffmpeg binaries must be extracted.
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.youtubedl.library)
    implementation(libs.youtubedl.ffmpeg)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)

    testImplementation(libs.junit)
}
