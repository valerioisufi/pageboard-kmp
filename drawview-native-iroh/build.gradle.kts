plugins {
    id("drawview.android.rust")
}

description = "The iroh bridge of DrawView for Android (libiroh_bridge.so, Rust), used by drawview-sync-iroh"

androidRust {
    crate.set(rootProject.layout.projectDirectory.dir("native/iroh-bridge"))
    library.set("iroh_bridge")
}

// The notices of the Rust crates inside the library
tasks.named<LicensesTask>("drawviewLicenses") {
    licenses.from(rootProject.file("native/iroh-bridge/THIRD_PARTY_NOTICES.md"))
}
