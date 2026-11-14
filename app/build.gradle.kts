plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    testOptions { unitTests.isIncludeAndroidResources = true }
    namespace = "com.monit.app"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig {
        applicationId = "com.monit.app"
        minSdk = libs.versions.androidMinSdk.get().toInt()
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testApplicationId = "com.monit.app.qa.performance.test"
        testInstrumentationRunner = "com.monit.app.backup.probe.BackupProbeRunner"
    }
    testBuildType = "performanceQa"
    buildTypes {
        create("performanceQa") {
            initWith(getByName("release"))
            applicationIdSuffix = ".qa.performance"
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            matchingFallbacks += listOf("release")
            resValue("string", "app_name", "MONIT 합성 성능 검증")
        }
        create("qa") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".qa"
            matchingFallbacks += listOf("debug")
        }
        create("backupQa") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".qa.backup"
            matchingFallbacks += listOf("debug")
            resValue("string", "app_name", "MONIT 백업 검증")
        }
    }
    sourceSets {
        listOf("debug", "release", "qa", "backupQa").forEach {
            getByName(it).kotlin.directories.add("src/backupProbeNoop/kotlin")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.androidJvmTarget.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.androidJvmTarget.get())
    }
    buildFeatures {
        compose = true
        resValues = true
    }
}

androidComponents {
    beforeVariants {
        if (it.buildType in setOf("debug", "performanceQa")) {
            checkNotNull(it.hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]).enable = true
        }
    }
}

tasks.withType<Test>().configureEach {
    if (name == "testPerformanceQaUnitTest") filter.includeTestsMatching("com.monit.app.backup.probe.*Test")
}

dependencies {
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.kotlin.test.junit)
    implementation(project(":core:common"))
    implementation(libs.androidx.hilt.viewmodel.compose)
    testImplementation(libs.kotlinx.coroutines.test)
    implementation(project(":core:navigation"))
    implementation(project(":feature:home"))
    implementation(project(":feature:transactions"))
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.navigation.testing)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.robolectric)
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
