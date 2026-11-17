//! Native v1 keeps the 12-slot `AuthActionRequest` layout; the typed country
//! code travels through the auth-only `extraJson` slot on both sides.

use cpplayer_provider_sdk::*;

fn encode_then_read(request: &AuthActionRequest) -> WireResult<AuthActionRequest> {
    let mut writer = WireWriter::new();
    request.write(&mut writer)?;
    let bytes = writer.finish();
    let mut reader = WireReader::new(&bytes)?;
    let decoded = AuthActionRequest::read(&mut reader)?;
    reader.finish()?;
    Ok(decoded)
}

#[test]
fn country_code_round_trips_through_native_v1_extra_json_slot() {
    let request = AuthActionRequest {
        action: "login.phone".to_string(),
        extra_json: Some(r#"{"source":"legacy"}"#.to_string()),
        country_code: Some("44".to_string()),
        ..Default::default()
    };

    let decoded = encode_then_read(&request).expect("valid Native v1 wire payload");
    assert_eq!(decoded.country_code.as_deref(), Some("44"));
    let extension: serde_json::Value =
        serde_json::from_str(decoded.extra_json.as_deref().unwrap()).unwrap();
    assert_eq!(extension["countryCode"], "44");
    assert_eq!(extension["source"], "legacy");
}

#[test]
fn non_string_country_code_is_rejected() {
    let request = AuthActionRequest {
        action: "login.phone".to_string(),
        extra_json: Some(r#"{"countryCode":86}"#.to_string()),
        ..Default::default()
    };

    assert!(matches!(
        encode_then_read(&request),
        Err(WireError::InvalidLength)
    ));
}

#[test]
fn omitted_country_code_preserves_default() {
    let request = AuthActionRequest {
        action: "login.phone".to_string(),
        ..Default::default()
    };

    let decoded = encode_then_read(&request).expect("valid Native v1 wire payload");
    assert_eq!(decoded.country_code, None);
    assert_eq!(decoded.extra_json, None);
}
