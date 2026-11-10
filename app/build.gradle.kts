plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.monit.app"
    compileSdk = MonitAndroidConfig.compileSdk
    defaultConfig {
        applicationId = "com.monit.app"
        minSdk = MonitAndroidConfig.minSdk
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = MonitAndroidConfig.javaVersion
        targetCompatibility = MonitAndroidConfig.javaVersion
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":feature:accounts"))
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
