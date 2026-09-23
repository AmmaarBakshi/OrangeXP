plugins {
    alias(libs.plugins.orangexp.android.compose)
    alias(libs.plugins.orangexp.hilt)
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.data)
    implementation(projects.core.designsystem)
    implementation(projects.core.engine)
    implementation(projects.core.ui)
    implementation(libs.androidx.glance.appwidget)
}
