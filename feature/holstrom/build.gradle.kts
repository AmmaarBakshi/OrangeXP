plugins {
    alias(libs.plugins.orangexp.android.feature)
}

dependencies {
    implementation(projects.core.work)
    implementation(projects.core.voice)
    implementation(projects.core.llm)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.glance.appwidget)
}
