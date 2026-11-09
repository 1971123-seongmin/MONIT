plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.monit.core.designsystem"
    compileSdk = MonitAndroidConfig.compileSdk
    defaultConfig { minSdk = MonitAndroidConfig.minSdk }
    compileOptions {
        sourceCompatibility = MonitAndroidConfig.javaVersion
        targetCompatibility = MonitAndroidConfig.javaVersion
    }
    buildFeatures { compose = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.robolectric)
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    systemProperty("monit.screenshot.dir", layout.buildDirectory.dir("reports/designsystem/screenshots").get().asFile.absolutePath)
}
