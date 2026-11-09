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
    val screenshotDir = layout.buildDirectory.dir("reports/designsystem/screenshots/$name")
    outputs.dir(screenshotDir).withPropertyName("monitScreenshots")
    // Test의 기본 작업 디렉터리는 이 모듈이다. 체크아웃 절대 경로를 JVM 입력에 넣지 않는다.
    systemProperty("monit.screenshot.dir", screenshotDir.get().asFile.relativeTo(projectDir).invariantSeparatorsPath)
}
