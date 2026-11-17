plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.demo.systemwp"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.demo.systemwp"
        minSdk = 29
        targetSdk = 37
        versionCode = 5
        versionName = "1.0.4"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
