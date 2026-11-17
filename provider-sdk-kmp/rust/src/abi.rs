//! Fixed ABI surface: session registry, JNI marshalling, C ABI entry points.
//!
//! The symbol names below are the contract. They are emitted by
//! [`crate::export_provider!`] in the author crate, so a provider never has to
//! hand-write (or mistype) them.

use crate::provider::{CancelToken, Failure, FailureCategory, ProviderContext};
use crate::wire::WireWriter;
use std::collections::HashMap;
use std::ffi::{c_char, CStr};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::ptr;
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

pub const ABI_VERSION: i32 = 1;

pub const INVOKE_OK: i32 = 0;
pub const INVOKE_BUFFER_TOO_SMALL: i32 = 1;
pub const INVOKE_FAILED: i32 = 2;

/// Author-supplied constructor, called once per session.
pub type Factory = fn(&ProviderContext) -> Box<dyn crate::MusicProvider>;

struct Session {
    provider: Mutex<Box<dyn crate::MusicProvider>>,
    context: ProviderContext,
    closed: AtomicBool,
}

static NEXT_HANDLE: AtomicI64 = AtomicI64::new(1);
static SESSIONS: OnceLock<Mutex<HashMap<i64, Arc<Session>>>> = OnceLock::new();

fn sessions() -> &'static Mutex<HashMap<i64, Arc<Session>>> {
    SESSIONS.get_or_init(|| Mutex::new(HashMap::new()))
}

/// Create one session. Returns 0 when the factory panics or rejects the context.
pub fn create_session(
    factory: Factory,
    provider_id: String,
    account_id: String,
    credential: Option<String>,
    settings_json: String,
) -> i64 {
    let context = ProviderContext {
        provider_id,
        account_id,
        credential,
        settings_json,
        cancel: CancelToken::default(),
    };
    let created = catch_unwind(AssertUnwindSafe(|| factory(&context)));
    let Ok(provider) = created else { return 0 };
    let handle = NEXT_HANDLE.fetch_add(1, Ordering::SeqCst);
    let session = Arc::new(Session {
        provider: Mutex::new(provider),
        context,
        closed: AtomicBool::new(false),
    });
    sessions().lock().unwrap_or_else(|e| e.into_inner()).insert(handle, session);
    handle
}

fn lookup(handle: i64) -> Option<Arc<Session>> {
    if handle == 0 {
        return None;
    }
    sessions().lock().unwrap_or_else(|e| e.into_inner()).get(&handle).cloned()
}

fn envelope(result: Result<Vec<u8>, Failure>) -> Vec<u8> {
    let mut out = Vec::with_capacity(128);
    match result {
        Ok(payload) => {
            out.push(0);
            out.extend_from_slice(&payload);
        }
        Err(failure) => {
            out.push(1);
            let mut writer = WireWriter::new();
            let acceptable = failure.is_acceptable();
            let category = if acceptable { failure.category.as_str() } else { "InvalidResponse" };
            let message = if acceptable { failure.message.as_str() } else { "Invalid provider failure" };
            // Both values are bounded above, so these writes cannot fail.
            writer.string(category).expect("failure category fits the wire limit");
            writer.string(message).expect("failure message fits the wire limit");
            out.extend_from_slice(&writer.finish());
        }
    }
    out
}

/// Run one typed operation. Never panics: panics become `Runtime` failures.
pub fn invoke_session(handle: i64, operation: i32, request: &[u8]) -> Option<Vec<u8>> {
    let session = lookup(handle)?;
    if session.closed.load(Ordering::SeqCst) {
        return None;
    }
    session.context.cancel.reset();
    let result = catch_unwind(AssertUnwindSafe(|| {
        let mut provider = session
            .provider
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        crate::operations::dispatch(provider.as_mut(), operation, request, &session.context)
    }));
    session.context.cancel.reset();
    Some(match result {
        Ok(result) => envelope(result),
        Err(_) => envelope(Err(Failure::new(FailureCategory::Runtime, "Provider panicked"))),
    })
}

pub fn cancel_session(handle: i64) {
    if let Some(session) = lookup(handle) {
        session.context.cancel.cancel();
    }
}

/// Removes the session. A concurrent invoke keeps its own `Arc` alive until it returns.
pub fn close_session(handle: i64) {
    if let Some(session) = sessions().lock().unwrap_or_else(|e| e.into_inner()).remove(&handle) {
        session.closed.store(true, Ordering::SeqCst);
        session.context.cancel.cancel();
    }
}

pub fn active_sessions() -> usize {
    sessions().lock().unwrap_or_else(|e| e.into_inner()).len()
}

unsafe fn read_cstr(value: *const c_char) -> Option<String> {
    if value.is_null() {
        return None;
    }
    CStr::from_ptr(value).to_str().ok().map(str::to_owned)
}

unsafe fn write_error(err: *mut c_char, err_cap: i32, message: &str) {
    if err.is_null() || err_cap <= 0 {
        return;
    }
    let capacity = (err_cap as usize).saturating_sub(1);
    let length = message.len().min(capacity);
    ptr::copy_nonoverlapping(message.as_ptr(), err.cast::<u8>(), length);
    *err.cast::<u8>().add(length) = 0;
}

/// `int64_t cpp_create(...)`. Always NUL-terminates `err` on failure.
pub unsafe fn cpp_create(
    factory: Factory,
    provider_id: *const c_char,
    account_id: *const c_char,
    credential: *const c_char,
    settings_json: *const c_char,
    err: *mut c_char,
    err_cap: i32,
) -> i64 {
    let result = catch_unwind(AssertUnwindSafe(|| {
        create_session(
            factory,
            read_cstr(provider_id).unwrap_or_default(),
            read_cstr(account_id).unwrap_or_default(),
            read_cstr(credential),
            read_cstr(settings_json).unwrap_or_else(|| "{}".to_string()),
        )
    }));
    match result {
        Ok(handle) if handle != 0 => handle,
        Ok(_) => {
            write_error(err, err_cap, "native session creation failed");
            0
        }
        Err(_) => {
            write_error(err, err_cap, "native session creation panicked");
            0
        }
    }
}

/// `int32_t cpp_invoke(...)`. `*out_len` is always written.
pub unsafe fn cpp_invoke(
    handle: i64,
    operation: i32,
    request: *const u8,
    request_len: i32,
    out: *mut u8,
    out_cap: i32,
    out_len: *mut i32,
) -> i32 {
    if !out_len.is_null() {
        *out_len = 0;
    }
    let request_bytes: &[u8] = if request.is_null() || request_len <= 0 {
        &[]
    } else {
        std::slice::from_raw_parts(request, request_len as usize)
    };
    let Some(response) = invoke_session(handle, operation, request_bytes) else {
        return INVOKE_FAILED;
    };
    let length = response.len() as i32;
    if !out_len.is_null() {
        *out_len = length;
    }
    if length > 0 && (out.is_null() || out_cap < length) {
        return INVOKE_BUFFER_TOO_SMALL;
    }
    if length > 0 {
        ptr::copy_nonoverlapping(response.as_ptr(), out, response.len());
    }
    INVOKE_OK
}

pub use jni::objects::{JByteArray, JClass, JString};
pub use jni::sys::{jbyteArray, jint, jlong};
pub use jni::JNIEnv;

fn string_arg(env: &mut JNIEnv, value: &JString<'_>) -> Option<String> {
    if value.is_null() {
        return None;
    }
    env.get_string(value).ok().map(Into::into)
}

pub unsafe fn jni_create(
    env: &mut JNIEnv<'_>,
    factory: Factory,
    provider_id: &JString<'_>,
    account_id: &JString<'_>,
    credential: &JString<'_>,
    settings_json: &JString<'_>,
) -> jlong {
    let provider_id = string_arg(env, provider_id).unwrap_or_default();
    let account_id = string_arg(env, account_id).unwrap_or_default();
    let credential = string_arg(env, credential);
    let settings_json = string_arg(env, settings_json).unwrap_or_else(|| "{}".to_string());
    create_session(factory, provider_id, account_id, credential, settings_json) as jlong
}

pub unsafe fn jni_invoke(
    env: &mut JNIEnv<'_>,
    handle: jlong,
    operation: jint,
    request: &JByteArray<'_>,
) -> jbyteArray {
    let request = env.convert_byte_array(request).unwrap_or_default();
    match invoke_session(handle, operation, &request) {
        Some(response) => env
            .byte_array_from_slice(&response)
            .map(|array| array.into_raw())
            .unwrap_or(ptr::null_mut()),
        None => ptr::null_mut(),
    }
}
