plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp)
    alias(libs.plugins.sqldelight)
}
kotlin {
    android { namespace = "dev.edgecompanion.shared"; compileSdk = 37; minSdk = 31; withHostTest {} }
    jvm()
    jvmToolchain(17)
    sourceSets {
        commonMain.dependencies { implementation(libs.coroutines.core); implementation(libs.sqldelight.runtime); implementation(libs.koin.core) }
        commonTest.dependencies { implementation(kotlin("test")); implementation(libs.coroutines.test); implementation(libs.koin.test) }
        androidMain.dependencies { implementation(libs.sqldelight.android); implementation(libs.sqlcipher); implementation(libs.sqlite) }
        jvmTest.dependencies { implementation(libs.sqldelight.jdbc) }
    }
}
sqldelight {
    databases { create("CompanionDatabase") { packageName.set("dev.edgecompanion.db"); verifyMigrations.set(true) } }
}
