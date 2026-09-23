import androidx.room.gradle.RoomExtension
import com.google.devtools.ksp.gradle.KspExtension
import com.orangexp.buildlogic.libs
import com.orangexp.buildlogic.library
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidRoomConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("androidx.room")
        pluginManager.apply("com.google.devtools.ksp")

        extensions.configure<KspExtension> {
            arg("room.generateKotlin", "true")
        }
        extensions.configure<RoomExtension> {
            // Exported schemas are committed and used to verify migrations.
            schemaDirectory("$projectDir/schemas")
        }
        dependencies {
            add("implementation", libs.library("androidx-room-runtime"))
            add("implementation", libs.library("androidx-room-ktx"))
            add("ksp", libs.library("androidx-room-compiler"))
            add("testImplementation", libs.library("androidx-room-testing"))
        }
    }
}
