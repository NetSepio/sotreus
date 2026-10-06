plugins {
    alias(libs.plugins.sotreus.android.feature)
}

android {
    namespace = "app.sotreus.feature.friends"
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.zxing.core)
    implementation(libs.zxing.android.embedded)
}
