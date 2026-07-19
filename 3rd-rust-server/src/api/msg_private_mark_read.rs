use super::Query;
use crate::error::Result;
/// 标记私信已读
/// 对应 Node.js module/msg_private_mark_read.js
use crate::request::{ApiClient, ApiResponse, CryptoType};
use serde_json::json;

impl ApiClient {
    /// 标记私信已读
    /// 对应 /msg/private/mark/read
    pub async fn msg_private_mark_read(&self, query: &Query) -> Result<ApiResponse> {
        let uid = query.get_or("uid", "0");
        let data = json!({
            "userId": uid,
            "userIds": format!("[{}]", uid)
        });
        self.request(
            "/api/msg/private/markread",
            data,
            query.to_option(CryptoType::Weapi),
        )
        .await
    }
}
