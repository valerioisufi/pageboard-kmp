//! The C API (`include/iroh_bridge.h`), read by cinterop on iOS.
//!
//! Every function returning `int32_t` gives `DV_IROH_OK` (0), `DV_IROH_TIMEOUT` (1),
//! `DV_IROH_CLOSED` (2) or `DV_IROH_ERROR` (-1, described by `dv_iroh_last_error`).

use std::cell::RefCell;
use std::ffi::{CStr, CString, c_char};
use std::ptr::null_mut;

use crate::bridge::{self, Failure};

pub const DV_IROH_OK: i32 = 0;
pub const DV_IROH_TIMEOUT: i32 = 1;
pub const DV_IROH_CLOSED: i32 = 2;
pub const DV_IROH_ERROR: i32 = -1;

thread_local! {
    static LAST_ERROR: RefCell<CString> = RefCell::new(CString::default());
}

/// Bytes given by the bridge, freed with [dv_iroh_bytes_free].
#[repr(C)]
pub struct DvIrohBytes {
    pub data: *mut u8,
    pub length: usize,
}

fn code<T>(result: bridge::Result<T>, out: impl FnOnce(T)) -> i32 {
    match result {
        Ok(value) => {
            out(value);
            DV_IROH_OK
        }
        Err(Failure::Timeout) => DV_IROH_TIMEOUT,
        Err(Failure::Closed) => DV_IROH_CLOSED,
        Err(Failure::Error(message)) => {
            LAST_ERROR.with(|last| *last.borrow_mut() = CString::new(message).unwrap_or_default());
            DV_IROH_ERROR
        }
    }
}

fn caught(body: impl FnOnce() -> i32) -> i32 {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(body)).unwrap_or_else(|_| {
        LAST_ERROR.with(|last| *last.borrow_mut() = CString::new("panic").unwrap());
        DV_IROH_ERROR
    })
}

unsafe fn text<'a>(value: *const c_char) -> &'a str {
    if value.is_null() { "" } else { unsafe { CStr::from_ptr(value) }.to_str().unwrap_or("") }
}

fn c_string(value: String) -> *mut c_char {
    CString::new(value).map(CString::into_raw).unwrap_or(null_mut())
}

/// The message of the last `DV_IROH_ERROR` of this thread, valid until its next call.
#[unsafe(no_mangle)]
pub extern "C" fn dv_iroh_last_error() -> *const c_char {
    LAST_ERROR.with(|last| last.borrow().as_ptr())
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn dv_iroh_bind(
    secret_key: *const u8,
    secret_key_length: usize,
    relays: *const c_char,
    token: *const c_char,
    alpn: *const c_char,
    endpoint: *mut u64,
) -> i32 {
    caught(|| unsafe {
        let key = if secret_key.is_null() { &[][..] } else { std::slice::from_raw_parts(secret_key, secret_key_length) };
        code(bridge::bind(key, text(relays), text(token), text(alpn)), |id| *endpoint = id)
    })
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn dv_iroh_address(endpoint: u64, timeout_ms: i64, address: *mut *mut c_char) -> i32 {
    caught(|| code(bridge::address(endpoint, timeout_ms), |value| unsafe { *address = c_string(value) }))
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn dv_iroh_id(endpoint: u64, id: *mut *mut c_char) -> i32 {
    caught(|| code(bridge::id(endpoint), |value| unsafe { *id = c_string(value) }))
}

#[unsafe(no_mangle)]
pub extern "C" fn dv_iroh_network_change(endpoint: u64) -> i32 {
    caught(|| code(bridge::network_change(endpoint), |_| {}))
}

#[unsafe(no_mangle)]
pub extern "C" fn dv_iroh_close_endpoint(endpoint: u64) {
    caught(|| {
        bridge::close_endpoint(endpoint);
        DV_IROH_OK
    });
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn dv_iroh_connect(endpoint: u64, address: *const c_char, timeout_ms: i64, connection: *mut u64) -> i32 {
    caught(|| unsafe { code(bridge::connect(endpoint, text(address), timeout_ms), |id| *connection = id) })
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn dv_iroh_accept(endpoint: u64, timeout_ms: i64, connection: *mut u64) -> i32 {
    caught(|| code(bridge::accept(endpoint, timeout_ms), |id| unsafe { *connection = id }))
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn dv_iroh_remote_id(connection: u64, id: *mut *mut c_char) -> i32 {
    caught(|| code(bridge::remote_id(connection), |value| unsafe { *id = c_string(value) }))
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn dv_iroh_path(connection: u64, kind: *mut i32, rtt_ms: *mut i64) -> i32 {
    caught(|| {
        code(bridge::path(connection), |(path, rtt)| unsafe {
            *kind = path;
            *rtt_ms = rtt;
        })
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn dv_iroh_close_connection(connection: u64) {
    caught(|| {
        bridge::close_connection(connection);
        DV_IROH_OK
    });
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn dv_iroh_open_stream(connection: u64, stream: *mut u64) -> i32 {
    caught(|| code(bridge::open_stream(connection), |id| unsafe { *stream = id }))
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn dv_iroh_accept_stream(connection: u64, timeout_ms: i64, stream: *mut u64) -> i32 {
    caught(|| code(bridge::accept_stream(connection, timeout_ms), |id| unsafe { *stream = id }))
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn dv_iroh_send(stream: u64, frame: *const u8, length: usize, timeout_ms: i64) -> i32 {
    caught(|| unsafe {
        let frame = if frame.is_null() { Vec::new() } else { std::slice::from_raw_parts(frame, length).to_vec() };
        code(bridge::send(stream, frame, timeout_ms), |_| {})
    })
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn dv_iroh_receive(stream: u64, timeout_ms: i64, max_size: usize, frame: *mut DvIrohBytes) -> i32 {
    caught(|| {
        code(bridge::receive(stream, timeout_ms, max_size), |bytes| unsafe {
            let bytes = bytes.into_boxed_slice();
            let length = bytes.len();
            *frame = DvIrohBytes { data: Box::into_raw(bytes).cast(), length };
        })
    })
}

/// Whether a `dv_iroh_receive` found the stream ended (or the stream is closed).
#[unsafe(no_mangle)]
pub extern "C" fn dv_iroh_is_ended(stream: u64) -> bool {
    bridge::is_ended(stream)
}

#[unsafe(no_mangle)]
pub extern "C" fn dv_iroh_close_stream(stream: u64) {
    caught(|| {
        bridge::close_stream(stream);
        DV_IROH_OK
    });
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn dv_iroh_string_free(value: *mut c_char) {
    if !value.is_null() {
        drop(unsafe { CString::from_raw(value) });
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn dv_iroh_bytes_free(bytes: DvIrohBytes) {
    if !bytes.data.is_null() {
        drop(unsafe { Box::from_raw(std::ptr::slice_from_raw_parts_mut(bytes.data, bytes.length)) });
    }
}
