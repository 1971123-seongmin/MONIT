plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.monit.feature.accounts"
    compileSdk = MonitAndroidConfig.compileSdk
    defaultConfig { minSdk = MonitAndroidConfig.minSdk }
    compileOptions {
        sourceCompatibility = MonitAndroidConfig.javaVersion
        targetCompatibility = MonitAndroidConfig.javaVersion
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
