plugins { id("com.android.application") }
android {
    namespace = "com.monit.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.monit.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies { implementation(project(":core:model")) }
