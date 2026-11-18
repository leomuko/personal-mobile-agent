plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}
val androidPressureMemoryTest = providers.gradleProperty("androidPressureMemoryTest")
    .map { it.toBooleanStrict() }.getOrElse(false)
val chatContextTokens = providers.gradleProperty("chatContextTokens")
    .map {
        require(it in setOf("2048", "4096", "8192")) { "chatContextTokens must be 2048, 4096, or 8192" }
        it
    }.getOrElse("2048")
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
        buildConfigField("boolean", "ANDROID_PRESSURE_MEMORY_TEST", "false")
        buildConfigField("int", "CHAT_CONTEXT_TOKENS", "2048")
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
            buildConfigField("boolean", "ANDROID_PRESSURE_MEMORY_TEST", androidPressureMemoryTest.toString())
            buildConfigField("int", "CHAT_CONTEXT_TOKENS", chatContextTokens)
            matchingFallbacks += listOf("debug")
        }
    }
    testBuildType = providers.gradleProperty("testBuildType").getOrElse("debug")
    sourceSets.getByName("androidTest").assets.srcDir(rootProject.file("planning/models"))
    sourceSets.getByName("test").kotlin.directories.add("src/testSupport/kotlin")
    sourceSets.getByName("androidTest").kotlin.directories.add("src/testSupport/kotlin")
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
    "secureQaImplementation"(libs.mediapipe.text)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.junit)
    androidTestImplementation(libs.tink)
}
