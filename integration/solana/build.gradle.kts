import java.io.File
import java.util.Properties
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity

// Solana Mobile: Mobile Wallet Adapter sign-in (SIWS) and mainnet Memo proof stamping.
// Only narrow values cross this boundary: a public key and a 32-byte commitment.
// NOWNODES_SOLANA_RPC_URL is compiled into this module, so it is inside both app flavors.
// A key in that URL can be extracted from the APK.
plugins {
    alias(libs.plugins.sotreus.android.library)
    alias(libs.plugins.sotreus.hilt)
    alias(libs.plugins.kotlin.serialization)
}

private val nowNodesKeys = listOf("NOWNODES_SOLANA_RPC_URL", "NOWNODES_SOLANA_API_KEY")

private fun Project.nowNodes(name: String): String {
    providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }?.let { return it }
    val loaded = providers.of(NowNodesConfigSource::class.java) {
        parameters.dotenv.set(rootProject.layout.projectDirectory.file(".env"))
        parameters.localProperties.set(rootProject.layout.projectDirectory.file("local.properties"))
    }.get()
    return loaded[name].orEmpty()
}

android {
    namespace = "app.sotreus.integration.solana"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        nowNodesKeys.forEach { name ->
            buildConfigField("String", name, nowNodes(name).asJavaStringLiteral())
        }
    }
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:crypto"))
    implementation(libs.solana.mwa.clientlib)
    implementation(libs.androidx.core.ktx)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}

private fun String.asJavaStringLiteral(): String = buildString {
    append('"')
    for (ch in this@asJavaStringLiteral) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            else -> append(ch)
        }
    }
    append('"')
}

abstract class NowNodesConfigSource : ValueSource<Map<String, String>, NowNodesConfigSource.Params> {
    interface Params : ValueSourceParameters {
        @get:Optional
        @get:InputFile
        @get:PathSensitive(PathSensitivity.NONE)
        val dotenv: RegularFileProperty

        @get:Optional
        @get:InputFile
        @get:PathSensitive(PathSensitivity.NONE)
        val localProperties: RegularFileProperty
    }

    override fun obtain(): Map<String, String> {
        val keys = listOf("NOWNODES_SOLANA_RPC_URL", "NOWNODES_SOLANA_API_KEY")
        val fromDotenv = readDotEnv(parameters.dotenv.asFile.orNull)
        val fromLocal = readProperties(parameters.localProperties.asFile.orNull)
        return keys.associateWith { key ->
            fromDotenv[key]?.takeIf { it.isNotBlank() } ?: fromLocal[key].orEmpty()
        }
    }

    private fun readDotEnv(file: File?): Map<String, String> {
        if (file == null || !file.isFile) return emptyMap()
        return file.readLines().mapNotNull { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@mapNotNull null
            val idx = line.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val key = line.substring(0, idx).trim()
            var value = line.substring(idx + 1).trim()
            if (value.length >= 2 && ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")))) {
                value = value.substring(1, value.length - 1)
            }
            key to value
        }.toMap()
    }

    private fun readProperties(file: File?): Map<String, String> {
        if (file == null || !file.isFile) return emptyMap()
        val loaded = Properties()
        file.inputStream().use(loaded::load)
        return loaded.stringPropertyNames().associateWith { loaded.getProperty(it).orEmpty() }
    }
}
