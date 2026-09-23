import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
}

group = "com.orangexp.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
    compileOnly(libs.room.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = libs.plugins.orangexp.android.application.get().pluginId
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = libs.plugins.orangexp.android.library.get().pluginId
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = libs.plugins.orangexp.android.compose.get().pluginId
            implementationClass = "AndroidComposeConventionPlugin"
        }
        register("androidFeature") {
            id = libs.plugins.orangexp.android.feature.get().pluginId
            implementationClass = "AndroidFeatureConventionPlugin"
        }
        register("androidRoom") {
            id = libs.plugins.orangexp.android.room.get().pluginId
            implementationClass = "AndroidRoomConventionPlugin"
        }
        register("hilt") {
            id = libs.plugins.orangexp.hilt.get().pluginId
            implementationClass = "HiltConventionPlugin"
        }
        register("rustAndroid") {
            id = libs.plugins.orangexp.rust.android.get().pluginId
            implementationClass = "RustAndroidConventionPlugin"
        }
        register("rustJvmTests") {
            id = libs.plugins.orangexp.rust.jvm.tests.get().pluginId
            implementationClass = "RustJvmTestsConventionPlugin"
        }
    }
}
