//! Author-facing types. Provider authors implement `MusicProvider` only.

use crate::wire::WireError;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FailureCategory {
    Unsupported,
    NotFound,
    Authentication,
    Network,
    RateLimit,
    Timeout,
    InvalidResponse,
    Runtime,
}

impl FailureCategory {
    pub const fn as_str(self) -> &'static str {
        match self {
            FailureCategory::Unsupported => "Unsupported",
            FailureCategory::NotFound => "NotFound",
            FailureCategory::Authentication => "Authentication",
            FailureCategory::Network => "Network",
            FailureCategory::RateLimit => "RateLimit",
            FailureCategory::Timeout => "Timeout",
            FailureCategory::InvalidResponse => "InvalidResponse",
            FailureCategory::Runtime => "Runtime",
        }
    }
}

/// Business failure. Text must never contain credentials or private URLs.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Failure {
    pub category: FailureCategory,
    pub message: String,
}

impl Failure {
    pub fn new(category: FailureCategory, message: impl Into<String>) -> Self {
        Self { category, message: message.into() }
    }

    /// The host replaces blank/oversized values with `InvalidResponse("Invalid provider failure")`.
    pub fn is_acceptable(&self) -> bool {
        !self.message.trim().is_empty()
            && self.message.chars().count() <= 4096
            && self.category.as_str().chars().count() <= 128
    }
}

impl std::fmt::Display for Failure {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}: {}", self.category.as_str(), self.message)
    }
}

impl std::error::Error for Failure {}

impl From<WireError> for Failure {
    fn from(error: WireError) -> Self {
        Failure::new(FailureCategory::Runtime, format!("Wire decoding error: {error}"))
    }
}

pub type ProviderResult<T> = Result<T, Failure>;

pub fn unsupported<T>() -> ProviderResult<T> {
    Err(Failure::new(FailureCategory::Unsupported, "Capability not supported"))
}

/// Cancellation for the invoke that is currently running. The host may call
/// cancel from another thread; poll this token between expensive steps.
#[derive(Clone, Default)]
pub struct CancelToken(Arc<AtomicBool>);

impl CancelToken {
    pub fn is_cancelled(&self) -> bool {
        self.0.load(Ordering::SeqCst)
    }

    pub fn reset(&self) {
        self.0.store(false, Ordering::SeqCst);
    }

    pub fn cancel(&self) {
        self.0.store(true, Ordering::SeqCst);
    }

    pub fn check(&self) -> ProviderResult<()> {
        if self.is_cancelled() {
            Err(Failure::new(FailureCategory::Timeout, "Operation cancelled"))
        } else {
            Ok(())
        }
    }
}

/// One immutable provider/account/credential/settings session.
///
/// `settings_json` is the raw JSON object the host passed to `create`; parse it
/// with the author's own JSON library. It is never re-serialized here.
#[derive(Clone)]
pub struct ProviderContext {
    pub provider_id: String,
    pub account_id: String,
    pub credential: Option<String>,
    pub settings_json: String,
    pub cancel: CancelToken,
}

impl ProviderContext {
    pub fn is_logged_in(&self) -> bool {
        self.credential.as_deref().is_some_and(|value| !value.trim().is_empty())
    }
}

impl std::fmt::Debug for ProviderContext {
    /// Credential is deliberately omitted so `{:?}` is safe to log.
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ProviderContext")
            .field("provider_id", &self.provider_id)
            .field("account_id", &self.account_id)
            .field("credential", &self.credential.as_ref().map(|_| "<redacted>"))
            .finish()
    }
}

pub const MAX_CAPABILITIES: usize = 256;
