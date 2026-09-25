import java.util.Properties

plugins {
    alias(libs.plugins.orangexp.android.application)
    alias(libs.plugins.orangexp.hilt)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing is read from keystore.properties (never committed) or the
// environment, so CI and contributors can build without access to the key.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use(::load)
}

fun signingValue(key: String, env: String): String? = keystoreProperties.getProperty(key) ?: System.getenv(env)

android {
    namespace = "com.orangexp.app"

    defaultConfig {
        applicationId = "com.orangexp.app"
        // CI passes these from the release tag; local builds use the defaults.
        versionCode = providers.gradleProperty("orangexp.versionCode").map(String::toInt).getOrElse(1)
        versionName = providers.gradleProperty("orangexp.versionName").getOrElse("0.1.0")
        vectorDrawables.useSupportLibrary = true

        // Package exactly the ABIs the Rust engine is built for. JNA ships more,
        // and a device must never pick an ABI without the engine library.
        ndk {
            abiFilters += providers.gradleProperty("orangexp.rust.abis").get().split(',').map(String::trim)
        }
    }

    signingConfigs {
        val storeFile = signingValue("storeFile", "ORANGEXP_KEYSTORE")
        if (storeFile != null) {
            create("release") {
                // Relative paths resolve from the repository root; CI passes an absolute path.
                this.storeFile = rootProject.file(storeFile)
                storePassword = signingValue("storePassword", "ORANGEXP_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "ORANGEXP_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "ORANGEXP_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // Holstrom's on-device language model runtime (~26 MB per ABI) ships for 64-bit ARM only:
        // a 2B model is out of reach for 32-bit phones, and emulators don't need it.
        jniLibs.excludes += listOf(
            "lib/armeabi-v7a/libllm_inference_engine_jni.so",
            "lib/x86/libllm_inference_engine_jni.so",
            "lib/x86_64/libllm_inference_engine_jni.so",
        )
    }
}

dependencies {
    implementation(projects.feature.today)
    implementation(projects.feature.history)
    implementation(projects.feature.academics)
    implementation(projects.feature.competitions)
    implementation(projects.feature.settings)
    implementation(projects.feature.widgets)
    implementation(projects.feature.holstrom)

    implementation(projects.core.common)
    implementation(projects.core.data)
    implementation(projects.core.designsystem)
    implementation(projects.core.work)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.iconsExtended)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.work)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.glance.appwidget)
}
