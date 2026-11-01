plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "dev.irshadm.volta.core.data"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":core:domain"))
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(project(":core:testing"))
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
