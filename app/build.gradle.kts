plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.laurencebrwn.ntsradio"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.laurencebrwn.ntsradio"
        minSdk = 24
        targetSdk = 37
        versionCode = 4
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // R8 full-mode optimisation breaks Kotlin stdlib visibility at runtime
            // (IllegalAccessError on startup); keep the release build unoptimised.
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.datasource)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
