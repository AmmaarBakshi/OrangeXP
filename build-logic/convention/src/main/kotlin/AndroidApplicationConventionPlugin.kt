import com.android.build.api.dsl.ApplicationExtension
import com.orangexp.buildlogic.OrangeXpSdk
import com.orangexp.buildlogic.configureAndroidCompose
import com.orangexp.buildlogic.configureKotlinAndroid
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        pluginManager.apply("org.jetbrains.kotlin.android")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

        extensions.configure<ApplicationExtension> {
            configureKotlinAndroid(this)
            configureAndroidCompose(this)
            defaultConfig.targetSdk = OrangeXpSdk.TARGET
        }
    }
}
