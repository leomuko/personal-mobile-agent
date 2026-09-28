plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "dev.edgecompanion.app"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.edgecompanion.app"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1-phase0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        buildConfigField("boolean", "SECURE_WINDOWS", "true")
    }
    buildTypes {
        getByName("debug") {
            buildConfigField("boolean", "SECURE_WINDOWS", "false")
            isPseudoLocalesEnabled = true
        }
        create("secureQa") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".secureqa"
            buildConfigField("boolean", "SECURE_WINDOWS", "true")
            matchingFallbacks += listOf("debug")
        }
    }
    testBuildType = providers.gradleProperty("testBuildType").getOrElse("debug")
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
    implementation(libs.compose.resources)
    implementation(libs.koin.android)
    implementation(libs.lifecycle.compose)
    implementation(project(":shared"))
    implementation(project(":ui"))
    implementation(libs.activity.compose)
    implementation(libs.coroutines.android)
    implementation(libs.lifecycle.runtime)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.sqlcipher)
    implementation(libs.sqldelight.android)
    implementation(libs.sqlite)
    implementation(libs.android.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.test.core)
    androidTestImplementation(libs.compose.test)
    androidTestImplementation(libs.compose.resources)
    debugImplementation(libs.compose.test.manifest)
    "secureQaImplementation"(libs.compose.test.manifest)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.junit)
}
