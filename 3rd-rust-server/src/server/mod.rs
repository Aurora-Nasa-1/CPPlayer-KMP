/// HTTP 服务模块 - 对应 Node.js 版本的 server.js
///
/// 使用 Axum 框架，将 ApiClient 的所有方法自动映射为 REST API 路由
/// 前端可以像调用 Node.js 版一样通过 HTTP 请求调用
pub mod middleware;
pub mod upload;

use crate::api::Query;
use crate::request::{ApiClient, ApiResponse};
use axum::extract::State;
use axum::http::header;
use axum::http::HeaderMap;
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
use serde_json::{json, Value};
use std::collections::HashMap;
use std::future::Future;
use std::pin::Pin;
use std::sync::Arc;

// ============================================================
//  服务器配置
// ============================================================

/// 服务器配置
#[derive(Debug, Clone)]
pub struct ServerConfig {
    /// 监听地址，默认 "0.0.0.0"
    pub host: String,
    /// 监听端口，默认 3000
    pub port: u16,
    /// CORS 允许的 Origin，None 表示允许所有
    pub cors_origin: Option<String>,
    /// 限流：时间窗口内最大请求数，0 表示不限流
    pub rate_limit: u64,
    /// 限流：时间窗口秒数
    pub rate_limit_window: u64,
}

impl Default for ServerConfig {
    fn default() -> Self {
        Self {
            host: "0.0.0.0".to_string(),
            port: 3000,
            cors_origin: None,
            rate_limit: 0,
            rate_limit_window: 60,
        }
    }
}

impl ServerConfig {
    /// 从环境变量读取配置
    /// - `NCM_HOST`: 监听地址
    /// - `NCM_PORT`: 监听端口
    /// - `CORS_ALLOW_ORIGIN`: CORS 允许的 Origin
    /// - `RATE_LIMIT`: 每个时间窗口的最大请求数（默认不限流）
    /// - `RATE_LIMIT_WINDOW`: 限流时间窗口秒数（默认 60）
    pub fn from_env() -> Self {
        Self {
            host: std::env::var("NCM_HOST").unwrap_or_else(|_| "0.0.0.0".to_string()),
            port: std::env::var("NCM_PORT")
                .ok()
                .and_then(|p| p.parse().ok())
                .unwrap_or(3000),
            cors_origin: std::env::var("CORS_ALLOW_ORIGIN").ok(),
            rate_limit: std::env::var("RATE_LIMIT")
                .ok()
                .and_then(|p| p.parse().ok())
                .unwrap_or(0),
            rate_limit_window: std::env::var("RATE_LIMIT_WINDOW")
                .ok()
                .and_then(|p| p.parse().ok())
                .unwrap_or(60),
        }
    }
}

// ============================================================
//  共享状态
// ============================================================

/// Axum 共享状态
#[derive(Clone)]
pub struct AppState {
    pub client: Arc<ApiClient>,
}

// ============================================================
//  查询参数提取
// ============================================================

/// 从 HTTP 请求中提取并合并查询参数
///
/// 合并优先级（后者覆盖前者）：Cookie < URL Query < POST Body
async fn extract_merged_query(
    headers: &HeaderMap,
    uri_query: Option<&str>,
    body: axum::body::Bytes,
    content_type: Option<&str>,
) -> Query {
    let mut query = Query::new();

    // 1. 提取 URL query 参数
    if let Some(qs) = uri_query {
        if let Ok(params) = serde_urlencoded::from_str::<HashMap<String, String>>(qs) {
            for (k, v) in params {
                query.params.insert(k, v);
            }
        }
    }

    // 2. 提取 POST body 参数
    if !body.is_empty() {
        let ct = content_type.unwrap_or("");
        if ct.contains("application/json") {
            if let Ok(map) = serde_json::from_slice::<HashMap<String, Value>>(&body) {
                for (k, v) in map {
                    let s = match &v {
                        Value::String(s) => s.clone(),
                        _ => v.to_string(),
                    };
                    query.params.insert(k, s);
                }
            }
        } else {
            // form-urlencoded 或其他格式
            if let Ok(params) = serde_urlencoded::from_bytes::<HashMap<String, String>>(&body) {
                for (k, v) in params {
                    query.params.insert(k, v);
                }
            }
        }
    }

    // 3. 处理 cookie
    // 优先使用参数中传入的 cookie
    if let Some(cookie_param) = query.params.remove("cookie") {
        query.cookie = Some(cookie_param);
    } else if let Some(cookie_header) = headers.get(header::COOKIE) {
        if let Ok(c) = cookie_header.to_str() {
            query.cookie = Some(c.to_string());
        }
    }

    // 4. 处理 realIP
    if let Some(real_ip) = query.params.remove("realIP") {
        query.real_ip = Some(real_ip);
    }

    // 5. 处理 proxy
    if let Some(proxy) = query.params.remove("proxy") {
        query.proxy = Some(proxy);
    }

    query
}

// ============================================================
//  响应构建
// ============================================================

/// 构建成功响应
///
/// 返回原始 Netease 响应 body，确保含有 `code` 字段以符合 KMP-PRO 接口规范。
/// Cookie 注入到 body.cookie 字段中。
fn build_success_response(api_resp: ApiResponse) -> Response {
    let status = axum::http::StatusCode::from_u16(api_resp.status as u16)
        .unwrap_or(axum::http::StatusCode::OK);

    let mut body = api_resp.body.clone();

    // 确保 body 中有 code 字段（KMP-PRO 接口规范要求）
    if let Value::Object(ref mut map) = body {
        if !map.contains_key("code") {
            map.insert("code".to_string(), Value::Number(serde_json::Number::from(200)));
        }
    }

    // 将 Cookie 注入到响应体中，方便移动端等调用方获取
    if !api_resp.cookie.is_empty() {
        if let Value::Object(ref mut map) = body {
            let cookie_str = api_resp.cookie.join("; ");
            map.insert("cookie".to_string(), Value::String(cookie_str));
        }
    }

    let mut response = if status == axum::http::StatusCode::FOUND {
        // 处理 302 重定向
        let redirect_url = body.get("redirectUrl").and_then(|u| u.as_str()).map(|s| s.to_string());
        let mut resp = (status, Json(body)).into_response();
        if let Some(url) = redirect_url {
            if let Ok(val) = header::HeaderValue::from_str(&url) {
                resp.headers_mut().insert(header::LOCATION, val);
            }
        }
        resp
    } else {
        (status, Json(body)).into_response()
    };

    // 设置 HTTP Header 中的 Set-Cookie
    for cookie_str in &api_resp.cookie {
        if let Ok(val) = header::HeaderValue::from_str(cookie_str) {
            response.headers_mut().append(header::SET_COOKIE, val);
        }
    }

    response
}

/// 构建错误响应
fn build_error_response(err: crate::error::NcmError) -> Response {
    use crate::error::NcmError;

    let (status, body) = match &err {
        NcmError::AuthRequired(msg) => (
            axum::http::StatusCode::UNAUTHORIZED,
            json!({ "code": 301, "msg": msg }),
        ),
        NcmError::InvalidParam(msg) => (
            axum::http::StatusCode::BAD_REQUEST,
            json!({ "code": 400, "msg": msg }),
        ),
        NcmError::RateLimited(msg) => (
            axum::http::StatusCode::TOO_MANY_REQUESTS,
            json!({ "code": 503, "msg": msg }),
        ),
        NcmError::Timeout(msg) => (
            axum::http::StatusCode::GATEWAY_TIMEOUT,
            json!({ "code": 504, "msg": msg }),
        ),
        NcmError::Api { code, msg } => {
            let http_status = axum::http::StatusCode::from_u16(*code as u16)
                .unwrap_or(axum::http::StatusCode::INTERNAL_SERVER_ERROR);
            (http_status, json!({ "code": code, "msg": msg }))
        }
        _ => (
            axum::http::StatusCode::INTERNAL_SERVER_ERROR,
            json!({ "code": 500, "msg": err.to_string() }),
        ),
    };

    (status, Json(body)).into_response()
}

// ============================================================
//  通用请求处理
// ============================================================

/// 通用 API 请求处理函数
async fn handle_api_request<F>(
    state: &AppState,
    headers: HeaderMap,
    uri: &axum::http::Uri,
    body: axum::body::Bytes,
    api_fn: F,
) -> Response
where
    F: for<'a> FnOnce(
        &'a ApiClient,
        &'a Query,
    ) -> Pin<
        Box<dyn Future<Output = crate::error::Result<ApiResponse>> + Send + 'a>,
    >,
{
    let path = uri.path().to_string();
    let start = std::time::Instant::now();

    let content_type = headers
        .get(header::CONTENT_TYPE)
        .and_then(|v| v.to_str().ok());

    let query = extract_merged_query(&headers, uri.query(), body, content_type).await;

    match api_fn(&state.client, &query).await {
        Ok(resp) => {
            tracing::info!("{} -> {} ({:.1?})", path, resp.status, start.elapsed());
            build_success_response(resp)
        }
        Err(e) => {
            tracing::warn!("{} -> ERROR: {} ({:.1?})", path, e, start.elapsed());
            build_error_response(e)
        }
    }
}

// ============================================================
//  路由注册宏
// ============================================================

/// 批量注册 API 路由的宏
///
/// 将每个 ApiClient 方法映射为 GET + POST 路由，
/// 同时注册 `/route` 和 `/api/route` 两个前缀路径，
/// 以兼容 CPPlayer BinaryProvider（发送到 `/api/...`）。
macro_rules! api_routes {
    ($router:expr, $( $method:ident => $route:expr ),* $(,)?) => {{
        let router = $router;
        $(
            let router = router.route(
                $route,
                get(|State(state): State<AppState>, headers: HeaderMap, uri: axum::http::Uri| async move {
                    handle_api_request(&state, headers, &uri, axum::body::Bytes::new(), |client, q| Box::pin(client.$method(q))).await
                })
                .post(|State(state): State<AppState>, headers: HeaderMap, uri: axum::http::Uri, body: axum::body::Bytes| async move {
                    handle_api_request(&state, headers, &uri, body, |client, q| Box::pin(client.$method(q))).await
                }),
            );
            let api_route = format!("/api{}", $route);
            let router = router.route(
                &api_route,
                get(|State(state): State<AppState>, headers: HeaderMap, uri: axum::http::Uri| async move {
                    handle_api_request(&state, headers, &uri, axum::body::Bytes::new(), |client, q| Box::pin(client.$method(q))).await
                })
                .post(|State(state): State<AppState>, headers: HeaderMap, uri: axum::http::Uri, body: axum::body::Bytes| async move {
                    handle_api_request(&state, headers, &uri, body, |client, q| Box::pin(client.$method(q))).await
                }),
            );
        )*
        router
    }};
}

// ============================================================
//  路由注册
// ============================================================

/// 注册所有 API 路由
///
/// 标准路由由 build.rs 自动从 src/api/mod.rs 扫描生成
/// 上传路由（avatar_upload, voice_upload）需要 multipart 处理，单独注册
fn register_routes(router: Router<AppState>) -> Router<AppState> {
    // 自动生成的标准路由（build.rs → api_routes_generated.rs）
    let router = { include!(concat!(env!("OUT_DIR"), "/api_routes_generated.rs")) };

    // 文件上传路由（特殊签名，需要 multipart 处理）
    let router = router
        .route("/avatar/upload", post(upload::handle_avatar_upload))
        .route("/voice/upload", post(upload::handle_voice_upload));

    router
}

// ============================================================
//  构建与启动
// ============================================================

/// 构建 Axum 应用（不启动监听）
///
/// 可用于测试或自定义部署
pub fn build_app(client: ApiClient) -> Router {
    let state = AppState {
        client: Arc::new(client),
    };

    let router = Router::new();
    let router = register_routes(router);

    // 根路由
    let router = router.route(
        "/",
        get(|| async {
            Json(json!({
                "code": 200,
                "msg": "NCM API Rust Server is running",
            }))
        }),
    );

    // 健康检查端点（KMP-PRO 兼容）
    let router = router.route(
        "/health",
        get(|| async {
            Json(json!({
                "code": 200,
                "status": "ok",
                "version": env!("CARGO_PKG_VERSION"),
                "features": {
                    "server": true,
                    "jni": cfg!(feature = "jni")
                }
            }))
        }),
    );

    router.layer(middleware::cors_layer(None)).with_state(state)
}

/// 构建带自定义配置的 Axum 应用
pub fn build_app_with_config(client: ApiClient, config: &ServerConfig) -> Router {
    let state = AppState {
        client: Arc::new(client),
    };

    let router = Router::new();
    let router = register_routes(router);

    let router = router.route(
        "/",
        get(|| async {
            Json(json!({
                "code": 200,
                "msg": "NCM API Rust Server is running",
            }))
        }),
    );

    // 健康检查端点（KMP-PRO 兼容）
    let router = router.route(
        "/health",
        get(|| async {
            Json(json!({
                "code": 200,
                "status": "ok",
                "version": env!("CARGO_PKG_VERSION"),
                "features": {
                    "server": true,
                    "jni": cfg!(feature = "jni")
                }
            }))
        }),
    );

    let router = router
        .layer(middleware::cors_layer(config.cors_origin.as_deref()))
        .with_state(state);

    // 限流中间件（rate_limit > 0 时启用）
    if config.rate_limit > 0 {
        let limiter = middleware::RateLimiter::new(config.rate_limit, config.rate_limit_window);
        router.layer(axum::middleware::from_fn_with_state(
            limiter,
            middleware::rate_limit_middleware,
        ))
    } else {
        router
    }
}

/// 启动 HTTP 服务器
pub async fn start_server(config: ServerConfig) {
    let client = ApiClient::new(None);
    let app = build_app_with_config(client, &config);

    let addr = format!("{}:{}", config.host, config.port);
    let listener = tokio::net::TcpListener::bind(&addr)
        .await
        .expect("Failed to bind address");

    tracing::info!("NCM API Server listening on http://{}", addr);

    axum::serve(listener, app).await.expect("Server error");
}
