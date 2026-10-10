import java.util.Locale
import java.util.Properties
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity

plugins {
    alias(libs.plugins.sotreus.android.application)
    alias(libs.plugins.sotreus.android.compose)
    alias(libs.plugins.sotreus.hilt)
}

private val releaseSigningFlavors = listOf(
    "generic" to "SOTREUS_GENERIC",
    "solanaMobile" to "SOTREUS_SOLANA_MOBILE",
)

private fun Project.releaseSigningSpec(flavor: String, envPrefix: String): Map<String, String>? {
    val envNames = listOf("STORE_FILE", "STORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")
    val fromEnv = envNames.associateWith { providers.environmentVariable("${envPrefix}_$it").orNull }
    val setCount = fromEnv.values.count { !it.isNullOrBlank() }
    if (setCount == envNames.size) {
        return mapOf(
            "storeFile" to fromEnv.getValue("STORE_FILE")!!,
            "storePassword" to fromEnv.getValue("STORE_PASSWORD")!!,
            "keyAlias" to fromEnv.getValue("KEY_ALIAS")!!,
            "keyPassword" to fromEnv.getValue("KEY_PASSWORD")!!,
        )
    }
    if (setCount != 0) {
        error(
            "$envPrefix release signing is incomplete. Set " +
                envNames.joinToString { "${envPrefix}_$it" } +
                ", or set none of them and use signing/$flavor.properties.",
        )
    }
    val loaded = providers.of(SigningPropertiesSource::class.java) {
        parameters.propertiesFile.set(rootProject.layout.projectDirectory.file("signing/$flavor.properties"))
    }.orNull
    if (loaded.isNullOrEmpty()) return null
    val required = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    val missing = required.filter { loaded[it].isNullOrBlank() }
    if (missing.isNotEmpty()) {
        error("signing/$flavor.properties is missing ${missing.joinToString()}")
    }
    return required.associateWith { loaded.getValue(it) }
}

android {
    namespace = "app.sotreus"

    defaultConfig {
        // One application ID for every distribution (and for iOS). Code namespace stays app.sotreus.
        applicationId = "com.sotreus.app"
        versionCode = 2
        versionName = "1.0.1"
    }

    // Release only. Debug keeps the debug keystore. A missing key fails the release build
    // rather than falling back to debug. Env vars win when all four for that flavor are set.
    signingConfigs {
        releaseSigningFlavors.forEach { (flavor, envPrefix) ->
            val spec = releaseSigningSpec(flavor, envPrefix) ?: return@forEach
            create("${flavor}Release") {
                storeFile = rootProject.file(spec.getValue("storeFile"))
                storePassword = spec.getValue("storePassword")
                keyAlias = spec.getValue("keyAlias")
                keyPassword = spec.getValue("keyPassword")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    // Flavors are distribution/build profiles (store, signing key, artifact type). They share the
    // application ID; which auth/settings UI shows is decided at runtime from the device.
    flavorDimensions += "distribution"
    productFlavors {
        create("generic") {
            dimension = "distribution"
            buildConfigField("String", "DISTRIBUTION", "\"generic\"")
        }
        create("solanaMobile") {
            dimension = "distribution"
            buildConfigField("String", "DISTRIBUTION", "\"solanaMobile\"")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

val releaseSigningConfigs = releaseSigningFlavors.associate { (flavor, _) ->
    flavor to android.signingConfigs.findByName("${flavor}Release")
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val flavor = variant.flavorName
            ?: throw GradleException("Release variant ${variant.name} has no product flavor.")
        val config = releaseSigningConfigs[flavor]
        if (config != null) {
            variant.signingConfig.from(config)
        } else {
            val flavorLabel = flavor.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
            }
            val requireSigning = tasks.register("require${flavorLabel}ReleaseSigning") {
                doLast {
                    throw GradleException(
                        "No release key for $flavor. Add signing/$flavor.properties " +
                            "(storeFile, storePassword, keyAlias, keyPassword), or set " +
                            "SOTREUS_*_STORE_FILE, _STORE_PASSWORD, _KEY_ALIAS and _KEY_PASSWORD. " +
                            "Release builds are not signed with the debug key.",
                    )
                }
            }
            tasks.configureEach {
                if (name == "pre${flavorLabel}ReleaseBuild") {
                    dependsOn(requireSigning)
                }
            }
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:ui"))
    implementation(project(":core:navigation"))
    implementation(project(":core:database"))
    implementation(project(":core:data"))
    implementation(project(":intelligence:fieldwatch"))
    implementation(project(":social:presence"))
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:now"))
    implementation(project(":feature:entity"))
    implementation(project(":feature:attention"))
    implementation(project(":feature:history"))
    implementation(project(":feature:place"))
    implementation(project(":feature:journey"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:friends"))
    implementation(project(":feature:proofs"))
    implementation(project(":feature:context"))
    implementation(project(":context:core"))
    // Wallet sign-in and proof stamping ship in every build; the UI appears only on Solana Mobile
    // devices (decided at runtime).
    implementation(project(":integration:solana"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(libs.junit)
}

abstract class SigningPropertiesSource :
    ValueSource<Map<String, String>, SigningPropertiesSource.Params> {
    interface Params : ValueSourceParameters {
        @get:Optional
        @get:InputFile
        @get:PathSensitive(PathSensitivity.NONE)
        val propertiesFile: RegularFileProperty
    }

    override fun obtain(): Map<String, String> {
        val file = parameters.propertiesFile.get().asFile
        if (!file.isFile) return emptyMap()
        val loaded = Properties()
        file.inputStream().use(loaded::load)
        return loaded.stringPropertyNames().associateWith { loaded.getProperty(it) }
    }
}
