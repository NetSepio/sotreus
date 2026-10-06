// Context sources (architecture handoff §14): aircraft (NETWORK), satellites (PREDICTED) and
// Remote ID (SENSED). Network clients here accept only an area or a catalog group.
plugins {
    alias(libs.plugins.sotreus.android.library)
    alias(libs.plugins.sotreus.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.sotreus.context"
}

dependencies {
    api(project(":core:data"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.predict4java) {
        exclude(group = "org.slf4j", module = "slf4j-simple")
    }
    testImplementation(libs.junit)
}
