// Sotreus intelligence: classification, baselines, attention, proximity, compare. Android-free.
plugins {
    alias(libs.plugins.sotreus.jvm.library)
}

dependencies {
    api(project(":core:model"))
    api(project(":intelligence:fieldwatch"))
}
