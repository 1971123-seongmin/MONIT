plugins {
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.monit.feature.home"
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
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    implementation(libs.androidx.hilt.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.compose.material3)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    implementation(platform(libs.androidx.compose.bom))
    implementation(project(":core:navigation"))
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.core)
}
