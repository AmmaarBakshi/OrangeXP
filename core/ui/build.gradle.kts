plugins {
    alias(libs.plugins.orangexp.android.compose)
}

dependencies {
    api(projects.core.designsystem)
    api(projects.core.data)
    api(projects.core.engine)
}
