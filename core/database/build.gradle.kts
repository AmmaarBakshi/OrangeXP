plugins {
    alias(libs.plugins.orangexp.android.library)
    alias(libs.plugins.orangexp.android.room)
    alias(libs.plugins.orangexp.hilt)
}

dependencies {
    implementation(projects.core.common)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
