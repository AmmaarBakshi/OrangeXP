plugins {
    alias(libs.plugins.orangexp.android.library)
    alias(libs.plugins.orangexp.hilt)
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.engine)
    implementation(libs.androidx.core.ktx)
}
