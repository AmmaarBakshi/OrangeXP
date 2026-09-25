import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.orangexp.buildlogic.OrangeXpSdk
import com.orangexp.buildlogic.rust.CargoNdkBuildTask
import com.orangexp.buildlogic.rust.UniffiBindgenTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import java.io.File

/** Configuration for [RustAndroidConventionPlugin]. */
abstract class RustAndroidExtension {
    /** Directory containing the crate's `Cargo.toml`. */
    abstract val crateDirectory: DirectoryProperty

    /** The `[lib] name` of the crate, e.g. `orangexp_core`. */
    abstract val libraryName: Property<String>

    abstract val features: ListProperty<String>
}

/**
 * Builds a Rust crate for Android and exposes it through UniFFI:
 *
 * 1. `cargoNdkBuild` cross-compiles the crate into a `jniLibs` layout;
 * 2. `uniffiBindgen` generates Kotlin bindings from the compiled library;
 * 3. both outputs are registered as generated sources of every variant, so a
 *    plain `./gradlew assembleDebug` always ships a matching library + bindings.
 *
 * ABIs come from the `orangexp.rust.abis` Gradle property.
 */
class RustAndroidConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        val rust = extensions.create<RustAndroidExtension>("orangexpRust")
        rust.features.convention(listOf("ffi"))

        val abis = providers.gradleProperty("orangexp.rust.abis")
            .map { value -> value.split(',').map(String::trim).filter(String::isNotEmpty) }
            .orElse(listOf("arm64-v8a", "armeabi-v7a", "x86_64"))

        val androidComponents = extensions.getByType<LibraryAndroidComponentsExtension>()
        // Prefer an explicit ANDROID_NDK_HOME, otherwise the newest NDK in the SDK.
        // A blank value (an unset variable forwarded by CI) counts as absent.
        val ndkHome = providers.environmentVariable("ANDROID_NDK_HOME")
            .filter { it.isNotBlank() }
            .orElse(androidComponents.sdkComponents.sdkDirectory.map { sdk -> latestNdk(sdk.asFile).orEmpty() })

        val cargoBuild = tasks.register<CargoNdkBuildTask>("cargoNdkBuild") {
            group = "rust"
            description = "Cross-compiles the Rust engine for Android."
            crateDirectory.set(rust.crateDirectory)
            sources.from(rust.crateDirectory.map { dir ->
                dir.asFileTree.matching {
                    include("Cargo.toml", "Cargo.lock", "src/**", "uniffi.toml")
                }
            })
            this.abis.set(abis)
            features.set(rust.features)
            apiLevel.set(OrangeXpSdk.MIN)
            this.ndkHome.set(ndkHome)
            outputDirectory.set(layout.buildDirectory.dir("rust/jniLibs"))
        }

        val bindgen = tasks.register<UniffiBindgenTask>("uniffiBindgen") {
            group = "rust"
            description = "Generates Kotlin bindings for the Rust engine."
            crateDirectory.set(rust.crateDirectory)
            config.set(rust.crateDirectory.file("uniffi.toml"))
            library.set(
                cargoBuild.flatMap { task ->
                    task.outputDirectory.zip(task.abis) { dir, list -> dir to list.first() }
                        .zip(rust.libraryName) { (dir, abi), name -> dir.file("$abi/lib$name.so") }
                },
            )
            outputDirectory.set(layout.buildDirectory.dir("generated/source/uniffi/kotlin"))
        }

        androidComponents.onVariants { variant ->
            variant.sources.jniLibs?.addGeneratedSourceDirectory(cargoBuild, CargoNdkBuildTask::outputDirectory)
            // KGP compiles .kt files from Java source directories; `sources.kotlin`
            // is not wired into Kotlin compilation before AGP 9.
            variant.sources.java?.addGeneratedSourceDirectory(bindgen, UniffiBindgenTask::outputDirectory)
        }
    }
}

private fun latestNdk(sdkDir: File): String? =
    File(sdkDir, "ndk").listFiles()
        ?.filter { it.isDirectory }
        ?.maxByOrNull { dir -> dir.name.split('.').map { it.toLongOrNull() ?: 0L }.fold(0L) { acc, part -> acc * 100_000 + part } }
        ?.absolutePath
