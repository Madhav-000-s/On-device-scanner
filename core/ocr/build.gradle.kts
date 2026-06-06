plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.madhav.scanner.core.ocr"
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
}

dependencies {
    implementation(project(":core:model"))

    // Bundled model, not the Play-Services-downloaded variant — must work offline (DESIGN.md §7.2).
    implementation(libs.mlkit.text.recognition)
    implementation(libs.opencv)

    implementation(libs.coroutines.core)

    testImplementation(libs.junit)
}
