import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** A feature module: Android library + Compose + Hilt + type-safe navigation routes. */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("sotreus.android.library")
        pluginManager.apply("sotreus.android.compose")
        pluginManager.apply("sotreus.hilt")
        pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")

        dependencies {
            add("implementation", project(":core:model"))
            add("implementation", project(":core:designsystem"))
            add("implementation", project(":core:ui"))
            add("implementation", project(":core:navigation"))
            add("implementation", project(":core:data"))
            add("implementation", project(":core:testing"))
            add("implementation", libs.lib("androidx-navigation-compose"))
            add("implementation", libs.lib("androidx-lifecycle-runtime-compose"))
            add("implementation", libs.lib("androidx-lifecycle-viewmodel-compose"))
            add("implementation", libs.lib("androidx-hilt-lifecycle-viewmodel-compose"))
            add("implementation", libs.lib("kotlinx-serialization-json"))
        }
    }
}
