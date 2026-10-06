// Android-free cryptography: proof canonicalisation, salted Merkle batches, presence tokens.
plugins {
    alias(libs.plugins.sotreus.jvm.library)
}

dependencies {
    implementation(libs.bouncycastle)
}
