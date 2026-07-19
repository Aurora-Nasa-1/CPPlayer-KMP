use crate::api::Query;
use crate::request::ApiClient;
use crate::server::{start_server, ServerConfig};
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::OnceLock;
use tokio::runtime::Runtime;
use tracing_subscriber::prelude::*;

static SERVER_STARTED: AtomicBool = AtomicBool::new(false);
static API_CLIENT: OnceLock<ApiClient> = OnceLock::new();
static TOKIO_RUNTIME: OnceLock<Runtime> = OnceLock::new();

fn get_client() -> &'static ApiClient {
    API_CLIENT.get_or_init(|| ApiClient::new(None))
}

fn get_runtime() -> &'static Runtime {
    TOKIO_RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .worker_threads(4)
            .thread_name("ncm-worker")
            .build()
            .expect("Failed to create Tokio runtime")
    })
}

/// # Safety
///
/// This function is called by the Android application via JNI.
/// It starts the Rust server in a background thread.
#[no_mangle]
#[allow(unsafe_code)]
pub unsafe extern "system" fn Java_cp_player_kmp_provider_JniProvider_startNativeServer(
    mut env: JNIEnv,
    _class: JClass,
    host: JString,
    port: i32,
) {
    if SERVER_STARTED.swap(true, Ordering::SeqCst) {
        return;
    }

    // Initialize logging (Android uses tracing-android, desktop uses tracing-subscriber)
    #[cfg(feature = "jni-android")]
    {
        let _ = tracing_subscriber::registry()
            .with(tracing_android::layer("ncm-rust").unwrap())
            .try_init();
    }
    #[cfg(all(feature = "jni", not(feature = "jni-android")))]
    {
        let _ = tracing_subscriber::fmt()
            .with_env_filter(
                tracing_subscriber::EnvFilter::try_from_default_env()
                    .unwrap_or_else(|_| "ncm_api_rs=info".into()),
            )
            .try_init();
    }

    let host_str: String = match env.get_string(&host) {
        Ok(s) => s.into(),
        Err(_) => "127.0.0.1".to_string(),
    };

    let config = ServerConfig {
        host: host_str,
        port: port as u16,
        ..Default::default()
    };

    let rt = get_runtime();
    rt.spawn(async move {
        start_server(config).await;
    });
}

/// # Safety
///
/// Direct API call via JNI.
#[no_mangle]
#[allow(unsafe_code)]
pub unsafe extern "system" fn Java_cp_player_kmp_provider_JniProvider_nativeCallApi(
    mut env: JNIEnv,
    _class: JClass,
    method: JString,
    params_json: JString,
) -> jstring {
    let method_str: String = env.get_string(&method).unwrap().into();
    let params_json_str: String = env.get_string(&params_json).unwrap().into();

    let rt = get_runtime();
    let result = rt.block_on(async move {
        let start = std::time::Instant::now();
        let client = get_client();
        let mut query = Query::new();

        if let Ok(params) = serde_json::from_str::<HashMap<String, String>>(&params_json_str) {
            for (k, v) in params {
                if k == "cookie" {
                    query.cookie = Some(v);
                } else {
                    query.params.insert(k, v);
                }
            }
        }

        let result = include!(concat!(env!("OUT_DIR"), "/jni_dispatcher_generated.rs"));

        match result {
            Ok(resp) => {
                let mut body = resp.body.clone();
                if !resp.cookie.is_empty() {
                    if let serde_json::Value::Object(ref mut map) = body {
                        let cookie_str = resp.cookie.join("; ");
                        map.insert("cookie".to_string(), serde_json::Value::String(cookie_str));
                    }
                }
                let s = serde_json::to_string(&body).unwrap_or_else(|_| "{}".to_string());
                tracing::info!("JNI {} took {:?}", method_str, start.elapsed());
                s
            }
            Err(e) => format!("{{\"code\": 500, \"msg\": \"{}\"}}", e),
        }
    });

    env.new_string(result).unwrap().into_raw()
}

/// # Safety
///
/// Analyzes an audio file and returns its features as a JSON string.
#[no_mangle]
#[allow(unsafe_code)]
pub unsafe extern "system" fn Java_cp_player_kmp_provider_JniProvider_analyzeAudioFile(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jstring {
    let path_str: String = match env.get_string(&path) {
        Ok(s) => s.into(),
        Err(_) => {
            return env
                .new_string("{\"error\": \"Invalid string\"}")
                .unwrap()
                .into_raw()
        }
    };

    let result = match crate::util::livesort::analyze_audio_file(&path_str) {
        Ok(features) => format!(
            "{{\"bpm\": {}, \"energy\": {}, \"brightness\": {}}}",
            features.bpm, features.energy, features.brightness
        ),
        Err(e) => format!("{{\"error\": \"{}\"}}", e.replace("\"", "\\\"")),
    };

    env.new_string(result).unwrap().into_raw()
}
