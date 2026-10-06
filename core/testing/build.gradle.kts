// FakeSotreusData: the sample world from the mocks, for previews and tests (HANDOFF_V1_UI.md §10).
plugins {
    alias(libs.plugins.sotreus.android.library)
}

android {
    namespace = "app.sotreus.core.testing"
}

dependencies {
    api(project(":core:data"))
}
