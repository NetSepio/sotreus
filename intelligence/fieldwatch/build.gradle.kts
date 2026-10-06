// Fieldwatch domain logic (MIT, Off Grid Pete LLC), ported with minimal changes so upstream
// fixes can be merged. Sotreus code wraps it in :intelligence:core; nothing else should call it.
plugins {
    alias(libs.plugins.sotreus.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
}
