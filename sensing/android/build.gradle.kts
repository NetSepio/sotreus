// Phone radios and location. Hardware access only; no classification or storage here.
plugins {
    alias(libs.plugins.sotreus.android.library)
    alias(libs.plugins.sotreus.hilt)
}

android {
    namespace = "app.sotreus.sensing"
}

dependencies {
    api(project(":core:model"))
    api(project(":intelligence:fieldwatch"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
}
