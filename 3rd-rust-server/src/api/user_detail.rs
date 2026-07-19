use super::Query;
use crate::error::Result;
/// 用户详情
/// 对应 Node.js module/user_detail.js
use crate::request::{ApiClient, ApiResponse, CryptoType};
use serde_json::json;

impl ApiClient {
    /// 用户详情
    /// 对应 /user/detail
    pub async fn user_detail(&self, query: &Query) -> Result<ApiResponse> {
        let uid = if query.params.contains_key("uid") {
            query.get_or("uid", "0")
        } else {
            query.get_or("id", "0")
        };
        let data = json!({
            "userId": uid
        });
        self.request(
            &format!("/api/v1/user/detail/{}", uid),
            data,
            query.to_option(CryptoType::Weapi),
        )
        .await
    }
}
