plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}
kotlin {
    android { namespace = "dev.edgecompanion.ui"; compileSdk = 37; minSdk = 31; androidResources.enable = true }
    jvm()
    jvmToolchain(17)
    sourceSets {
        commonMain.dependencies {
            implementation(libs.navigation.compose)
            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.lifecycle.compose)
            implementation(project(":shared"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(libs.compose.backhandler)
            implementation(compose.components.resources)
            implementation(libs.material3)
            implementation(libs.icons)
        }
    }
}
compose.resources { publicResClass = true; packageOfResClass = "dev.edgecompanion.ui.resources" }
