//! CPPlayer `kmp-sdk-v1` provider SDK for Rust.
//!
//! Implement [`MusicProvider`] (only the capabilities you actually serve) and
//! call [`export_provider!`]. The SDK owns Wire encoding, operation dispatch,
//! session handles, cancellation and both native ABIs.
//!
//! ```ignore
//! use cpplayer_provider_sdk::*;
//!
//! struct MyProvider;
//!
//! impl MusicProvider for MyProvider {
//!     fn capabilities(&self, _context: &ProviderContext) -> Vec<&'static str> {
//!         vec![Capability::SEARCH]
//!     }
//!     fn search(&mut self, request: SearchRequest, _context: &ProviderContext) -> ProviderResult<TrackPage> {
//!         Ok(TrackPage { items: Vec::new(), total: Some(0), has_more: Some(false) })
//!     }
//! }
//!
//! fn create(context: &ProviderContext) -> Box<dyn MusicProvider> {
//!     Box::new(MyProvider)
//! }
//!
//! export_provider!(create);
//! ```

pub mod abi;
mod models;
mod operations;
pub mod provider;
pub mod wire;
#[cfg(test)]
mod vectors;

pub use models::*;
pub use operations::{all_capabilities, dispatch, tags, Capability, MusicProvider};
pub use provider::*;
pub use wire::{WireError, WireReader, WireResult, WireWriter, MAX_BYTES, MAX_ITEMS, MAX_SAFE_INTEGER};

pub use abi::active_sessions;

/// Emits the fixed JNI and C ABI symbols for `$factory`.
///
/// `$factory` must be a `fn(&ProviderContext) -> Box<dyn MusicProvider>`.
#[macro_export]
macro_rules! export_provider {
    ($factory:path) => {
        #[no_mangle]
        pub extern "C" fn cpp_abi_version() -> i32 {
            $crate::abi::ABI_VERSION
        }

        /// # Safety
        /// Pointers must be valid for the duration of this call.
        #[no_mangle]
        pub unsafe extern "C" fn cpp_create(
            provider_id: *const ::std::ffi::c_char,
            account_id: *const ::std::ffi::c_char,
            credential: *const ::std::ffi::c_char,
            settings_json: *const ::std::ffi::c_char,
            err: *mut ::std::ffi::c_char,
            err_cap: i32,
        ) -> i64 {
            $crate::abi::cpp_create(
                $factory,
                provider_id,
                account_id,
                credential,
                settings_json,
                err,
                err_cap,
            )
        }

        /// # Safety
        /// `request`/`out` must be valid for the given lengths; `out_len` must be writable.
        #[no_mangle]
        pub unsafe extern "C" fn cpp_invoke(
            handle: i64,
            operation: i32,
            request: *const u8,
            request_len: i32,
            out: *mut u8,
            out_cap: i32,
            out_len: *mut i32,
        ) -> i32 {
            $crate::abi::cpp_invoke(
                handle, operation, request, request_len, out, out_cap, out_len,
            )
        }

        #[no_mangle]
        pub extern "C" fn cpp_cancel(handle: i64) {
            $crate::abi::cancel_session(handle)
        }

        #[no_mangle]
        pub extern "C" fn cpp_close(handle: i64) {
            $crate::abi::close_session(handle)
        }

        #[no_mangle]
        pub unsafe extern "system" fn Java_cp_player_sdk_jni_NativeSession_createNative(
            mut env: $crate::abi::JNIEnv,
            _class: $crate::abi::JClass,
            provider_id: $crate::abi::JString,
            account_id: $crate::abi::JString,
            credential: $crate::abi::JString,
            settings_json: $crate::abi::JString,
        ) -> $crate::abi::jlong {
            $crate::abi::jni_create(
                &mut env,
                $factory,
                &provider_id,
                &account_id,
                &credential,
                &settings_json,
            )
        }

        #[no_mangle]
        pub unsafe extern "system" fn Java_cp_player_sdk_jni_NativeSession_invokeNative(
            mut env: $crate::abi::JNIEnv,
            _class: $crate::abi::JClass,
            handle: $crate::abi::jlong,
            operation: $crate::abi::jint,
            request: $crate::abi::JByteArray,
        ) -> $crate::abi::jbyteArray {
            $crate::abi::jni_invoke(&mut env, handle, operation, &request)
        }

        #[no_mangle]
        pub unsafe extern "system" fn Java_cp_player_sdk_jni_NativeSession_cancelNative(
            _env: $crate::abi::JNIEnv,
            _class: $crate::abi::JClass,
            handle: $crate::abi::jlong,
        ) {
            $crate::abi::cancel_session(handle)
        }

        #[no_mangle]
        pub unsafe extern "system" fn Java_cp_player_sdk_jni_NativeSession_closeNative(
            _env: $crate::abi::JNIEnv,
            _class: $crate::abi::JClass,
            handle: $crate::abi::jlong,
        ) {
            $crate::abi::close_session(handle)
        }
    };
}
