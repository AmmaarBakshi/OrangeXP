import com.orangexp.buildlogic.libs
import com.orangexp.buildlogic.library
import com.orangexp.buildlogic.rust.CargoHostBuildTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType

/**
 * Lets JVM unit tests call the real Rust engine: builds the crate for the host
 * and points JNA at it. Apply to modules whose tests exercise the Kotlin ↔ Rust
 * bridge end to end.
 */
class RustJvmTestsConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        val crateDirectory = rootProject.layout.projectDirectory.dir("orangexp-core")
        val hostBuild = tasks.register<CargoHostBuildTask>("cargoHostBuild") {
            group = "rust"
            description = "Builds the Rust engine for the host so unit tests can load it."
            this.crateDirectory.set(crateDirectory)
            features.set(listOf("ffi"))
        }
        val libraryPath = crateDirectory.dir("target/release").asFile.absolutePath
        tasks.withType<Test>().configureEach {
            dependsOn(hostBuild)
            systemProperty("jna.library.path", libraryPath)
        }
        dependencies {
            // The desktop JNA jar carries the host jnidispatch library.
            add("testImplementation", libs.library("jna"))
        }
    }
}
