package com.orangexp.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

object OrangeXpSdk {
    const val COMPILE = 36
    const val TARGET = 36

    /** Android 9: first release reporting keyguard events through UsageStats. */
    const val MIN = 28
}

internal fun Project.configureKotlinAndroid(commonExtension: CommonExtension<*, *, *, *, *, *>) {
    commonExtension.apply {
        compileSdk = OrangeXpSdk.COMPILE
        defaultConfig {
            minSdk = OrangeXpSdk.MIN
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        testOptions {
            unitTests.isIncludeAndroidResources = true
            unitTests.isReturnDefaultValues = true
        }
    }

    extensions.configure<KotlinAndroidProjectExtension> {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-opt-in=kotlin.RequiresOptIn",
                "-Xconsistent-data-class-copy-visibility",
                "-Xannotation-default-target=param-property",
            )
        }
    }

    dependencies {
        add("testImplementation", libs.library("junit4"))
        add("testImplementation", libs.library("kotlin-test"))
        add("testImplementation", libs.library("kotlinx-coroutines-test"))
    }
}
