import java.util.Properties

plugins {
    alias(libs.plugins.sotreus.android.application)
    alias(libs.plugins.sotreus.android.compose)
    alias(libs.plugins.sotreus.hilt)
}

android {
    namespace = "app.sotreus"

    defaultConfig {
        // One application ID for every distribution (and for iOS). Code namespace stays app.sotreus.
        applicationId = "com.sotreus.app"
        versionCode = 1
        versionName = "1.0.0"
    }

    // Each distribution is signed with its own release key. Put the key details in
    // `signing/<flavor>.properties` (storeFile, storePassword, keyAlias, keyPassword; never
    // committed). Until a key exists, release builds fall back to the debug key.
    signingConfigs {
        listOf("generic", "solanaMobile").forEach { flavor ->
            val props = rootProject.file("signing/$flavor.properties")
            if (props.isFile) {
                val p = Properties().apply { props.inputStream().use(::load) }
                create("${flavor}Release") {
                    storeFile = rootProject.file(p.getProperty("storeFile"))
                    storePassword = p.getProperty("storePassword")
                    keyAlias = p.getProperty("keyAlias")
                    keyPassword = p.getProperty("keyPassword")
                }
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
            signingConfig = signingConfigs.findByName("genericRelease") ?: signingConfigs.getByName("debug")
        }
        create("solanaMobile") {
            dimension = "distribution"
            buildConfigField("String", "DISTRIBUTION", "\"solanaMobile\"")
            signingConfig = signingConfigs.findByName("solanaMobileRelease") ?: signingConfigs.getByName("debug")
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
