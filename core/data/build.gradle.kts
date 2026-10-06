// Repositories, settings, the observation pipeline and the session service. Android library.
plugins {
    alias(libs.plugins.sotreus.android.library)
    alias(libs.plugins.sotreus.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.sotreus.core.data"
}

dependencies {
    api(project(":core:model"))
    api(project(":core:database"))
    api(project(":intelligence:core"))
    api(project(":sensing:android"))
    implementation(project(":core:crypto"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.room.runtime)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlinx.coroutines.test)
}
