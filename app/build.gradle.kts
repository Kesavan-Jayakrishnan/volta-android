import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Optional release signing, from local.properties or environment variables (CI). Never committed.
//   VOLTA_STORE_FILE=/path/to/release.jks
//   VOLTA_STORE_PASSWORD=…  VOLTA_KEY_ALIAS=…  VOLTA_KEY_PASSWORD=…
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun secret(name: String): String? = (localProps.getProperty(name) ?: System.getenv(name))?.takeIf { it.isNotBlank() }

android {
    namespace = "dev.irshadm.volta"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.irshadm.volta.oss"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        secret("VOLTA_STORE_FILE")?.let { store ->
            create("release") {
                storeFile = file(store)
                storePassword = secret("VOLTA_STORE_PASSWORD")
                keyAlias = secret("VOLTA_KEY_ALIAS")
                keyPassword = secret("VOLTA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":feature:onboarding"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(project(":core:testing"))
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
