plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.madhav.scanner.core.ml"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // Without this, AAPT compresses the .tflite model, LiteRT can't memory-map it,
    // and startup pays a full decompress + heap copy (DESIGN.md §3).
    androidResources {
        noCompress += listOf("tflite", "lite")
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:bench"))
    implementation(project(":core:camera"))

    implementation(libs.camerax.core)
    implementation(libs.tflite)
    implementation(libs.tflite.gpu)
    implementation(libs.tflite.gpu.api)
    implementation(libs.tflite.support)

    implementation(libs.opencv)

    testImplementation(libs.junit)
}
