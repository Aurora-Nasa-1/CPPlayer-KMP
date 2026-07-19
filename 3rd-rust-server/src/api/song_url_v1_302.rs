use super::Query;
use crate::error::Result;
/// 获取客户端歌曲下载链接 - v1 (302 重定向)
/// 对应 Node.js module/song_url_v1_302.js
use crate::request::{ApiClient, ApiResponse, CryptoType};
use serde_json::json;

impl ApiClient {
    /// 获取客户端歌曲下载链接 (302 重定向)
    /// 对应 /song/url/v1/302
    ///
    /// 实现音质自动降级逻辑：
    /// 依次尝试请求的音质等级，若无有效 URL 则逐级降低音质 (hires -> lossless -> exhigh -> higher -> standard)
    pub async fn song_url_v1_302(&self, query: &Query) -> Result<ApiResponse> {
        let id = query.get_or("id", "0");
        let requested_level = query.get_or("level", "standard");

        let levels = ["sky", "hires", "lossless", "exhigh", "higher", "standard"];

        // 找到请求等级在序列中的位置，只尝试当前及更低的等级
        let start_index = levels.iter().position(|&l| l == requested_level).unwrap_or(levels.len() - 1);
        let mut final_response = None;

        for &level in &levels[start_index..] {
            // 1. 尝试 download 接口
            let data = json!({
                "id": &id,
                "immerseType": "c51",
                "level": level,
            });

            if let Ok(response) = self.request("/api/song/enhance/download/url/v1", data, query.to_option(CryptoType::default())).await {
                if let Some(url) = response.body.get("data").and_then(|d| d.get(0)).and_then(|item| item.get("url")).and_then(|u| u.as_str()) {
                    if !url.is_empty() && url != "null" {
                        return Ok(ApiResponse {
                            status: 302,
                            body: json!({ "redirectUrl": url, "level": level }),
                            cookie: response.cookie,
                        });
                    }
                }
                final_response = Some(response);
            }

            // 2. 尝试 player 接口
            let mut fallback_data = json!({
                "ids": format!("[{}]", id),
                "level": level,
                "encodeType": "flac",
            });
            if level == "sky" {
                fallback_data["immerseType"] = json!("c51");
            }

            if let Ok(fallback) = self.request("/api/song/enhance/player/url/v1", fallback_data, query.to_option(CryptoType::default())).await {
                if let Some(url) = fallback.body.get("data").and_then(|d| d.get(0)).and_then(|item| item.get("url")).and_then(|u| u.as_str()) {
                    if !url.is_empty() && url != "null" {
                        return Ok(ApiResponse {
                            status: 302,
                            body: json!({ "redirectUrl": url, "level": level }),
                            cookie: fallback.cookie,
                        });
                    }
                }
                final_response = Some(fallback);
            }
        }

        // 如果全部尝试都失败，返回最后一个响应
        match final_response {
            Some(resp) => Ok(resp),
            None => Err(crate::error::NcmError::Unknown("No audio URL found after quality fallback".to_string())),
        }
    }
}
