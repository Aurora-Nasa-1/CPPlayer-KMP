use super::Query;
use crate::error::Result;
/// DIFM电台 - 收藏列表
/// 对应 Node.js module/dj_difm_subscribe_channels_get.js
use crate::request::{ApiClient, ApiResponse, CryptoType};
use serde_json::json;

impl ApiClient {
    /// DIFM电台 - 收藏列表
    /// 对应 /dj/difm/subscribe/channels/get
    pub async fn dj_difm_subscribe_channels_get(&self, query: &Query) -> Result<ApiResponse> {
        let data = json!({
            "sources": query.get_or("sources", "[0]")
        });
        self.request(
            "/api/dj/difm/subscribe/channels/get/v2",
            data,
            query.to_option(CryptoType::Weapi),
        )
        .await
    }
}
