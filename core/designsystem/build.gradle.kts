import java.nio.file.Files

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
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.robolectric)
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    val buildRoot = layout.buildDirectory
    val screenshotRoot = layout.buildDirectory.dir("reports/designsystem/screenshots")
    val screenshotDir = layout.buildDirectory.dir("reports/designsystem/screenshots/$name")
    outputs.dir(screenshotDir).withPropertyName("monitScreenshots")
    // Test의 기본 작업 디렉터리는 이 모듈이다. 체크아웃 절대 경로를 JVM 입력에 넣지 않는다.
    systemProperty(
        "monit.screenshot.dir",
        screenshotDir.get().asFile.relativeTo(projectDir).invariantSeparatorsPath
    )
    doFirst {
        val buildPath = buildRoot.get().asFile.canonicalFile.toPath()
        val rootPath = screenshotRoot.get().asFile.canonicalFile.toPath()
        val outputPath = screenshotDir.get().asFile.canonicalFile.toPath()
        check(rootPath.startsWith(buildPath) && outputPath.startsWith(rootPath) && outputPath != rootPath)
        // 부분 실행의 이전 조건 이미지를 새 캐시에 섞지 않는다. 하위 심볼릭 링크를 따라가지 않는다.
        if (Files.exists(outputPath)) {
            Files.walk(outputPath).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
            }
        }
        Files.createDirectories(outputPath)
    }
}
