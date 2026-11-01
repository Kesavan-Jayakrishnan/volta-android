plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.irshadm.volta.core.designsystem"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    buildFeatures { compose = true }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.material3)
    api(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}
