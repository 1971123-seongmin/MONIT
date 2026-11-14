plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.monit.core.data"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.androidMinSdk.get().toInt() }
    buildTypes {
        create("performanceQa") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
        }
    }
    sourceSets {
        getByName("performanceQa").resources.directories.add("src/test/resources")
        listOf("debug", "release").forEach {
            getByName(it).kotlin.directories.add("src/backupProbeNoop/kotlin")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.androidJvmTarget.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.androidJvmTarget.get())
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

androidComponents {
    beforeVariants(selector().withBuildType("performanceQa")) {
        checkNotNull(it.hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]).enable = true
    }
}

tasks.withType<Test>().configureEach {
    systemProperty("monit.roomSchemas", layout.projectDirectory.dir("schemas").asFile.absolutePath)
    if (name == "testPerformanceQaUnitTest") filter.includeTestsMatching("com.monit.core.data.backup.BackupProbe*Test")
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(libs.androidx.room.runtime)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.androidx.room.compiler)
    ksp(libs.hilt.compiler)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
}
