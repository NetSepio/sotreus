plugins {
    alias(libs.plugins.sotreus.android.feature)
}

android {
    namespace = "app.sotreus.feature.entity"
}

dependencies {
    implementation(libs.androidx.activity.compose)
}
