package com.orangexp.buildlogic.rust

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

/**
 * Cross-compiles a Rust crate for Android ABIs with `cargo ndk` and lays the
 * shared libraries out as a `jniLibs` directory.
 */
abstract class CargoNdkBuildTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Internal
    abstract val crateDirectory: DirectoryProperty

    @get:Input
    abstract val abis: ListProperty<String>

    @get:Input
    abstract val features: ListProperty<String>

    @get:Input
    abstract val apiLevel: Property<Int>

    @get:Input
    @get:Optional
    abstract val ndkHome: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun build() {
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        output.mkdirs()
        val args = buildList {
            add("cargo")
            add("ndk")
            abis.get().forEach { add("-t"); add(it) }
            add("-P"); add(apiLevel.get().toString())
            add("-o"); add(output.absolutePath)
            add("build")
            add("--release")
            add("--lib")
            if (features.get().isNotEmpty()) {
                add("--features"); add(features.get().joinToString(","))
            }
        }
        execOperations.exec {
            workingDir = crateDirectory.get().asFile
            ndkHome.orNull?.takeIf { it.isNotEmpty() }?.let { environment("ANDROID_NDK_HOME", it) }
            commandLine(args)
        }
    }
}

/**
 * Generates Kotlin bindings from a compiled library with UniFFI's library mode.
 * The generator is the crate's own `uniffi-bindgen` binary, so its version always
 * matches the runtime.
 */
abstract class UniffiBindgenTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val library: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val config: RegularFileProperty

    @get:Internal
    abstract val crateDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        output.mkdirs()
        execOperations.exec {
            workingDir = crateDirectory.get().asFile
            commandLine(
                "cargo", "run", "--quiet", "-p", "uniffi-bindgen", "--",
                "generate",
                "--library", library.get().asFile.absolutePath,
                "--language", "kotlin",
                "--config", config.get().asFile.absolutePath,
                "--no-format",
                "--out-dir", output.absolutePath,
            )
        }
    }
}

/**
 * Builds the crate for the host machine so JVM unit tests can load the real
 * engine through JNA. Cargo's own incremental build makes repeated runs cheap.
 */
@org.gradle.work.DisableCachingByDefault(because = "Delegates up-to-date checks to cargo")
abstract class CargoHostBuildTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {

    @get:Internal
    abstract val crateDirectory: DirectoryProperty

    @get:Input
    abstract val features: ListProperty<String>

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun build() {
        execOperations.exec {
            workingDir = crateDirectory.get().asFile
            commandLine(
                buildList {
                    addAll(listOf("cargo", "build", "--release", "--lib"))
                    if (features.get().isNotEmpty()) {
                        add("--features"); add(features.get().joinToString(","))
                    }
                },
            )
        }
    }
}
