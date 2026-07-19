use super::Query;
use crate::error::Result;
/// DIFM电台 - 分类
/// 对应 Node.js module/dj_difm_all_style_channel.js
use crate::request::{ApiClient, ApiResponse, CryptoType};
use serde_json::json;

impl ApiClient {
    /// DIFM电台 - 分类
    /// 对应 /dj/difm/all/style/channel
    pub async fn dj_difm_all_style_channel(&self, query: &Query) -> Result<ApiResponse> {
        let data = json!({
            "sources": query.get_or("sources", "[0]")
        });
        self.request(
            "/api/dj/difm/all/style/channel/v2",
            data,
            query.to_option(CryptoType::Weapi),
        )
        .await
    }
}
