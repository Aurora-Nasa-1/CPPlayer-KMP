use super::Query;
use crate::error::Result;
/// 用户歌单
/// 对应 Node.js module/user_playlist.js
use crate::request::{ApiClient, ApiResponse, CryptoType};
use serde_json::json;

impl ApiClient {
    /// 用户歌单
    /// 对应 /user/playlist
    pub async fn user_playlist(&self, query: &Query) -> Result<ApiResponse> {
        let uid = if query.params.contains_key("uid") {
            query.get_or("uid", "0")
        } else {
            query.get_or("id", "0")
        };
        let data = json!({
            "uid": uid,
            "userId": uid,
            "limit": query.get_or("limit", "100").parse::<i64>().unwrap_or(100),
            "offset": query.get_or("offset", "0").parse::<i64>().unwrap_or(0),
            "includeVideo": "true"
        });
        self.request(
            "/api/user/playlist",
            data,
            query.to_option(CryptoType::Weapi),
        )
        .await
    }
}
