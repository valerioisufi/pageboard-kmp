//! The iroh endpoint of Pageboard: connections to other devices by their key, direct or through a
//! relay, carrying whole frames (see `pageboard-sync-iroh`).
//!
//! The endpoints, connections and streams are numbers ([bridge]): a number closed or never given
//! is refused, so that a call racing a close fails instead of touching freed memory. The C API
//! ([ffi], iOS) and the JNI functions ([jni], Android and the desktop JVM) are thin layers over
//! [bridge].

pub mod address;
pub mod bridge;
pub mod ffi;
#[cfg(not(target_os = "ios"))]
pub mod jni;
