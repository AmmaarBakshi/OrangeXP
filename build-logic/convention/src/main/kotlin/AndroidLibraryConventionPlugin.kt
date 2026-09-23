import com.android.build.api.dsl.LibraryExtension
import com.orangexp.buildlogic.OrangeXpSdk
import com.orangexp.buildlogic.configureKotlinAndroid
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        pluginManager.apply("org.jetbrains.kotlin.android")

        extensions.configure<LibraryExtension> {
            configureKotlinAndroid(this)
            testOptions.targetSdk = OrangeXpSdk.TARGET
            lint.targetSdk = OrangeXpSdk.TARGET
            // Namespaces follow the module path: :core:data -> com.orangexp.core.data
            namespace = "com.orangexp" + path.replace(':', '.').replace('-', '_')
            defaultConfig.consumerProguardFiles("consumer-rules.pro")
        }
    }
}
