plugins {
    alias(libs.plugins.orangexp.android.library)
    alias(libs.plugins.orangexp.hilt)
    alias(libs.plugins.orangexp.rust.jvm.tests)
}

dependencies {
    api(projects.core.common)
    api(projects.core.engine)
    implementation(projects.core.database)
    implementation(projects.core.sensing)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.turbine)
    testImplementation(libs.androidx.room.runtime)
}
