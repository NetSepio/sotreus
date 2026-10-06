plugins {
    alias(libs.plugins.sotreus.android.library)
    alias(libs.plugins.sotreus.hilt)
    alias(libs.plugins.room)
}

android {
    namespace = "app.sotreus.core.database"
}

room {
    // Exported schemas are committed so every migration can be tested. Never destructive in release.
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    api(project(":core:model"))
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.coroutines.core)
}
