plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}
val androidPressureMemoryTest = providers.gradleProperty("androidPressureMemoryTest")
    .map { it.toBooleanStrict() }.getOrElse(false)
// Ordinary chat uses 4096 in every build type (user decision 2026-11-18). The override is
// QA-only: it changes secureQa alone, so 8192 stays reachable only in secureQa builds.
val ordinaryChatContextTokens = "4096"
val chatContextTokens = providers.gradleProperty("chatContextTokens")
    .map {
        require(it in setOf("2048", "4096", "8192")) { "chatContextTokens must be 2048, 4096, or 8192" }
        it
    }.getOrElse(ordinaryChatContextTokens)
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
        buildConfigField("int", "CHAT_CONTEXT_TOKENS", ordinaryChatContextTokens)
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
    androidTestImplementation(libs.test.espresso.core)
    constraints {
        val concurrentFuturesReason =
            "Espresso 3.7.0 and androidx.test:core 1.7.0 request concurrent-futures and " +
                "concurrent-futures-ktx 1.2.0, but AGP runtime consistency holds the test runtime " +
                "at 1.1.0; hold the androidTest compile classpath at the same 1.1.0 so tests " +
                "cannot compile against 1.2.0-only APIs. Only 1.1.0 of concurrent-futures is in " +
                "the offline cache."
        androidTestImplementation("androidx.concurrent:concurrent-futures") {
            version { strictly("1.1.0") }
            because(concurrentFuturesReason)
        }
        androidTestImplementation("androidx.concurrent:concurrent-futures-ktx") {
            version { strictly("1.1.0") }
            because(concurrentFuturesReason)
        }
    }
    androidTestImplementation(libs.tink)
}
