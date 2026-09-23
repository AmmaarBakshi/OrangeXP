plugins {
    alias(libs.plugins.orangexp.android.library)
    alias(libs.plugins.orangexp.hilt)
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.data)
    implementation(libs.androidx.core.ktx)
    api(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(libs.androidx.work.testing)
}
