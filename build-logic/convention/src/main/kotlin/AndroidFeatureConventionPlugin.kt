import com.orangexp.buildlogic.libs
import com.orangexp.buildlogic.library
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * A feature module: one screen (or a small group), its ViewModels and its
 * navigation entry point. Features depend on core modules, never on each other.
 */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("orangexp.android.compose")
        pluginManager.apply("orangexp.hilt")
        pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")

        dependencies {
            add("implementation", project(":core:common"))
            add("implementation", project(":core:data"))
            add("implementation", project(":core:designsystem"))
            add("implementation", project(":core:engine"))
            add("implementation", project(":core:ui"))

            add("implementation", libs.library("androidx-hilt-lifecycle-viewmodel-compose"))
            add("implementation", libs.library("androidx-lifecycle-runtime-compose"))
            add("implementation", libs.library("androidx-lifecycle-viewmodel-compose"))
            add("implementation", libs.library("androidx-navigation-compose"))
            add("implementation", libs.library("androidx-compose-material3"))
            add("implementation", libs.library("androidx-compose-material-iconsExtended"))
            add("implementation", libs.library("kotlinx-serialization-json"))

            add("testImplementation", libs.library("turbine"))
        }
    }
}
