//! The JNI functions of `io.github.valerioisufi.drawview.sync.iroh.IrohJni` (Android and the
//! desktop JVM), through the raw interface of `jni-sys`: numbers, strings and byte arrays only.
//!
//! A number of 0 or less tells what went wrong: 0 a timeout, -1 closed, -2 an error (see
//! `lastError`).

use std::cell::RefCell;
use std::ffi::{CStr, CString, c_char};
use std::ptr::null_mut;

use jni_sys::{JNIEnv, jboolean, jbyteArray, jint, jlong, jobject, jstring};

use crate::bridge::{self, Failure};

thread_local! {
    static LAST_ERROR: RefCell<String> = const { RefCell::new(String::new()) };
}

fn handle(result: bridge::Result<u64>) -> jlong {
    match result {
        Ok(id) => id as jlong,
        Err(Failure::Timeout) => 0,
        Err(Failure::Closed) => -1,
        Err(Failure::Error(message)) => {
            LAST_ERROR.with(|last| *last.borrow_mut() = message);
            -2
        }
    }
}

fn caught<T>(fallback: T, body: impl FnOnce() -> T) -> T {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(body)).unwrap_or_else(|panic| {
        let message = panic
            .downcast_ref::<&str>()
            .map(|s| s.to_string())
            .or_else(|| panic.downcast_ref::<String>().cloned())
            .unwrap_or_default();
        LAST_ERROR.with(|last| *last.borrow_mut() = format!("panic: {message}"));
        fallback
    })
}

unsafe fn string(env: *mut JNIEnv, value: jstring) -> String {
    if value.is_null() {
        return String::new();
    }
    unsafe {
        let chars = ((**env).v1_1.GetStringUTFChars)(env, value, null_mut());
        if chars.is_null() {
            return String::new();
        }
        let text = CStr::from_ptr(chars).to_string_lossy().into_owned();
        ((**env).v1_1.ReleaseStringUTFChars)(env, value, chars);
        text
    }
}

unsafe fn new_string(env: *mut JNIEnv, value: &str) -> jstring {
    let Ok(value) = CString::new(value) else { return null_mut() };
    unsafe { ((**env).v1_1.NewStringUTF)(env, value.as_ptr() as *const c_char) }
}

unsafe fn bytes(env: *mut JNIEnv, array: jbyteArray) -> Vec<u8> {
    if array.is_null() {
        return Vec::new();
    }
    unsafe {
        let length = ((**env).v1_1.GetArrayLength)(env, array).max(0) as usize;
        let mut bytes = vec![0u8; length];
        ((**env).v1_1.GetByteArrayRegion)(env, array, 0, length as i32, bytes.as_mut_ptr().cast());
        bytes
    }
}

unsafe fn new_bytes(env: *mut JNIEnv, value: &[u8]) -> jbyteArray {
    unsafe {
        let array = ((**env).v1_1.NewByteArray)(env, value.len() as i32);
        if !array.is_null() {
            ((**env).v1_1.SetByteArrayRegion)(env, array, 0, value.len() as i32, value.as_ptr().cast());
        }
        array
    }
}

// The functions take the object `IrohJni` (`this`) second: they are its members

/// Gives iroh the Java VM and the application context, before any endpoint (Android only).
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_io_github_valerioisufi_drawview_sync_iroh_IrohJni_initAndroid(
    env: *mut JNIEnv,
    _this: jobject,
    context: jobject,
) {
    caught((), || unsafe {
        let mut vm: *mut jni_sys::JavaVM = null_mut();
        if ((**env).v1_1.GetJavaVM)(env, &mut vm) != 0 {
            return;
        }
        // Kept for the life of the process
        let context = ((**env).v1_1.NewGlobalRef)(env, context);
        iroh::dns::install_android_jni_context(vm.cast(), context.cast());
    })
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_io_github_valerioisufi_drawview_sync_iroh_IrohJni_lastError(
    env: *mut JNIEnv,
    _this: jobject,
) -> jstring {
    let message = LAST_ERROR.with(|last| last.borrow().clone());
    unsafe { new_string(env, &message) }
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_io_github_valerioisufi_drawview_sync_iroh_IrohJni_bind(
    env: *mut JNIEnv,
    _this: jobject,
    secret_key: jbyteArray,
    relays: jstring,
    token: jstring,
    alpn: jstring,
) -> jlong {
    let (secret_key, relays, token, alpn) =
        unsafe { (bytes(env, secret_key), string(env, relays), string(env, token), string(env, alpn)) };
    caught(-2, || handle(bridge::bind(&secret_key, &relays, &token, &alpn)))
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_io_github_valerioisufi_drawview_sync_iroh_IrohJni_address(
    env: *mut JNIEnv,
    _this: jobject,
    endpoint: jlong,
    timeout_ms: jlong,
) -> jstring {
    match caught(Err(Failure::Error("panic".into())), || bridge::address(endpoint as u64, timeout_ms)) {
        Ok(address) => unsafe { new_string(env, &address) },
        Err(_) => null_mut(),
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_io_github_valerioisufi_drawview_sync_iroh_IrohJni_id(
    env: *mut JNIEnv,
    _this: jobject,
    endpoint: jlong,
) -> jstring {
    match bridge::id(endpoint as u64) {
        Ok(id) => unsafe { new_string(env, &id) },
        Err(_) => null_mut(),
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_io_github_valerioisufi_drawview_sync_iroh_IrohJni_networkChange(
    _env: *mut JNIEnv,
    _this: jobject,
    endpoint: jlong,
) {
    caught((), || {
        let _ = bridge::network_change(endpoint as u64);
    })
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_io_github_valerioisufi_drawview_sync_iroh_IrohJni_closeEndpoint(
    _env: *mut JNIEnv,
    _this: jobject,
    endpoint: jlong,
) {
    caught((), || bridge::close_endpoint(endpoint as u64))
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_io_github_valerioisufi_drawview_sync_iroh_IrohJni_connect(
    env: *mut JNIEnv,
    _this: jobject,
    endpoint: jlong,
    address: jstring,
    timeout_ms: jlong,
) -> jlong {
    let address = unsafe { string(env, address) };
    caught(-2, || handle(bridge::connect(endpoint as u64, &address, timeout_ms)))
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_io_github_valerioisufi_drawview_sync_iroh_IrohJni_accept(
    _env: *mut JNIEnv,
    _this: jobject,
    endpoint: jlong,
    timeout_ms: jlong,
) -> jlong {
    caught(-2, || handle(bridge::accept(endpoint as u64, timeout_ms)))
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_io_github_valerioisufi_drawview_sync_iroh_IrohJni_remoteId(
    env: *mut JNIEnv,
    _this: jobject,
    connection: jlong,
) -> jstring {
    match bridge::remote_id(connection as u64) {
        Ok(id) => unsafe { new_string(env, &id) },
        Err(_) => null_mut(),
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_io_github_valerioisufi_drawview_sync_iroh_IrohJni_pathKind(
    _env: *mut JNIEnv,
    _this: jobject,
    connection: jlong,
) -> jint {
    bridge::path(connection as u64).map(|(kind, _)| kind).unwrap_or(0)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_io_github_valerioisufi_drawview_sync_iroh_IrohJni_pathRtt(
    _env: *mut JNIEnv,
    _this: jobject,
    connection: jlong,
) -> jlong {
    bridge::path(connection as u64).map(|(_, rtt)| rtt).unwrap_or(0)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_io_github_valerioisufi_drawview_sync_iroh_IrohJni_closeConnection(
    _env: *mut JNIEnv,
    _this: jobject,
    connection: jlong,
) {
    caught((), || bridge::close_connection(connection as u64))
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_io_github_valerioisufi_drawview_sync_iroh_IrohJni_openStream(
    _env: *mut JNIEnv,
    _this: jobject,
    connection: jlong,
) -> jlong {
    caught(-2, || handle(bridge::open_stream(connection as u64)))
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_io_github_valerioisufi_drawview_sync_iroh_IrohJni_acceptStream(
    _env: *mut JNIEnv,
    _this: jobject,
    connection: jlong,
    timeout_ms: jlong,
) -> jlong {
    caught(-2, || handle(bridge::accept_stream(connection as u64, timeout_ms)))
}

/// 0 sent, 1 timeout (not queued), 2 closed, -1 an error.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_io_github_valerioisufi_drawview_sync_iroh_IrohJni_send(
    env: *mut JNIEnv,
    _this: jobject,
    stream: jlong,
    frame: jbyteArray,
    timeout_ms: jlong,
) -> jint {
    let frame = unsafe { bytes(env, frame) };
    caught(-1, || match bridge::send(stream as u64, frame, timeout_ms) {
        Ok(()) => 0,
        Err(Failure::Timeout) => 1,
        Err(Failure::Closed) => 2,
        Err(Failure::Error(message)) => {
            LAST_ERROR.with(|last| *last.borrow_mut() = message);
            -1
        }
    })
}

/// The next frame, or null: a timeout, or the end of the stream when `isEnded` says so.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_io_github_valerioisufi_drawview_sync_iroh_IrohJni_receive(
    env: *mut JNIEnv,
    _this: jobject,
    stream: jlong,
    timeout_ms: jlong,
) -> jbyteArray {
    match caught(Err(Failure::Closed), || bridge::receive(stream as u64, timeout_ms)) {
        Ok(frame) => unsafe { new_bytes(env, &frame) },
        Err(_) => null_mut(),
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_io_github_valerioisufi_drawview_sync_iroh_IrohJni_isEnded(
    _env: *mut JNIEnv,
    _this: jobject,
    stream: jlong,
) -> jboolean {
    bridge::is_ended(stream as u64) as jboolean
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_io_github_valerioisufi_drawview_sync_iroh_IrohJni_closeStream(
    _env: *mut JNIEnv,
    _this: jobject,
    stream: jlong,
) {
    caught((), || bridge::close_stream(stream as u64))
}
