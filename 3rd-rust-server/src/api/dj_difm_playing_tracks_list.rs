use super::Query;
use crate::error::Result;
/// DIFM电台 - 播放列表
/// 对应 Node.js module/dj_difm_playing_tracks_list.js
use crate::request::{ApiClient, ApiResponse, CryptoType};
use serde_json::json;

impl ApiClient {
    /// DIFM电台 - 播放列表
    /// 对应 /dj/difm/playing/tracks/list
    pub async fn dj_difm_playing_tracks_list(&self, query: &Query) -> Result<ApiResponse> {
        let data = json!({
            "limit": query.get_or("limit", "5").parse::<i64>().unwrap_or(5),
            "source": query.get_or("source", "0").parse::<i64>().unwrap_or(0),
            "channelId": query.get_or("channelId", "")
        });
        self.request(
            "/api/dj/difm/playing/tracks/list",
            data,
            query.to_option(CryptoType::Weapi),
        )
        .await
    }
}
