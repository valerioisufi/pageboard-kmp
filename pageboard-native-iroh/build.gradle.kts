plugins {
    id("pageboard.android.rust")
}

description = "The iroh bridge of Pageboard for Android (libiroh_bridge.so, Rust), used by pageboard-sync-iroh"

androidRust {
    crate.set(rootProject.layout.projectDirectory.dir("native/iroh-bridge"))
    library.set("iroh_bridge")
}

// The notices of the Rust crates inside the library
tasks.named<LicensesTask>("pageboardLicenses") {
    licenses.from(rootProject.file("native/iroh-bridge/THIRD_PARTY_NOTICES.md"))
}
