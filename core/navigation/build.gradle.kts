plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.monit.core.navigation"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.androidMinSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.androidJvmTarget.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.androidJvmTarget.get())
    }
    buildFeatures { compose = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    api(libs.androidx.navigation.runtime)
    api(libs.androidx.compose.runtime)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.androidx.navigation.compose)
    testImplementation(libs.androidx.navigation.testing)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.kotlinx.serialization.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.robolectric)
}
