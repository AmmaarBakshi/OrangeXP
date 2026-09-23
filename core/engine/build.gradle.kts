plugins {
    alias(libs.plugins.orangexp.android.library)
    alias(libs.plugins.orangexp.hilt)
    alias(libs.plugins.orangexp.rust.android)
    alias(libs.plugins.orangexp.rust.jvm.tests)
}

orangexpRust {
    crateDirectory.set(rootProject.layout.projectDirectory.dir("orangexp-core"))
    libraryName.set("orangexp_core")
}

dependencies {
    // Generated bindings call into the native library through JNA.
    api(variantOf(libs.jna) { artifactType("aar") })
}
