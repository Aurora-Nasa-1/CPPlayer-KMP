use super::Query;
use crate::error::Result;
/// DIFM电台 - 收藏频道
/// 对应 Node.js module/dj_difm_channel_subscribe.js
use crate::request::{ApiClient, ApiResponse, CryptoType};
use serde_json::json;

impl ApiClient {
    /// DIFM电台 - 收藏频道
    /// 对应 /dj/difm/channel/subscribe
    pub async fn dj_difm_channel_subscribe(&self, query: &Query) -> Result<ApiResponse> {
        let data = json!({
            "id": query.get_or("id", "")
        });
        self.request(
            "/api/dj/difm/channel/subscribe",
            data,
            query.to_option(CryptoType::Weapi),
        )
        .await
    }
}
