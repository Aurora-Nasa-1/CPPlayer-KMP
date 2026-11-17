//! End-to-end proof that `export_provider!` emits a usable session surface.
//!
//! These tests call the same `cpp_*` and `NativeSession` helpers the host uses,
//! so a rename or a mismatched signature fails here instead of in the app.

use cpplayer_provider_sdk::*;

/// The smallest useful provider: three capabilities, no network.
struct Catalog;

impl MusicProvider for Catalog {
    fn capabilities(&self, _context: &ProviderContext) -> Vec<&'static str> {
        vec![
            Capability::SEARCH,
            Capability::GET,
            Capability::RESOLVE,
        ]
    }

    fn search(&mut self, request: SearchRequest, _context: &ProviderContext) -> ProviderResult<TrackPage> {
        let items = vec![Track {
            id: "001".into(),
            title: format!("Catalog {}", request.keyword),
            artists: vec![Artist { id: Some("artist-A".into()), name: "Artist".into() }],
            duration_ms: 9_500,
            album: None,
            cover_url: None,
        }];
        Ok(TrackPage { items, total: Some(1), has_more: Some(false) })
    }

    fn get(&mut self, request: IdRequest, _context: &ProviderContext) -> ProviderResult<Track> {
        if request.id.is_empty() {
            return Err(Failure::new(FailureCategory::InvalidResponse, "Empty ID"));
        }
        Ok(Track {
            id: request.id,
            title: "Track".into(),
            artists: Vec::new(),
            duration_ms: 1,
            album: None,
            cover_url: None,
        })
    }

    fn resolve(&mut self, request: ResolveRequest, _context: &ProviderContext) -> ProviderResult<MediaResource> {
        Ok(MediaResource {
            url: format!("https://cdn.example.test/{}", request.id),
            actual_quality: "lossless".into(),
            headers: vec![Header { name: "X-Token".into(), value: "stream".into() }],
            expires_at_epoch_ms: None,
            format: Some("FLAC".into()),
            bitrate: None,
            size_bytes: None,
        })
    }
}

fn create(_context: &ProviderContext) -> Box<dyn MusicProvider> {
    Box::new(Catalog)
}

export_provider!(create);

fn encode<T>(value: &T, write: fn(&T, &mut WireWriter) -> Result<(), WireError>) -> Vec<u8> {
    let mut writer = WireWriter::new();
    write(value, &mut writer).unwrap();
    writer.finish()
}

fn create_session() -> i64 {
    let handle = create_session_for_test();
    assert_ne!(handle, 0);
    handle
}

fn create_session_for_test() -> i64 {
    let mut err = [0i8; 256];
    unsafe {
        cpp_create(
            c"example".as_ptr(),
            c"account".as_ptr(),
            std::ptr::null(),
            c"{\"origin\":\"https://api.example.test\"}".as_ptr(),
            err.as_mut_ptr(),
            err.len() as i32,
        )
    }
}

#[test]
fn abi_version_is_probed_first() {
    assert_eq!(cpp_abi_version(), 1);
}

#[test]
fn capabilities_come_back_as_a_wire_record() {
    let handle = create_session();
    let request = encode(&Bytes { value: Vec::new() }, Bytes::write);
    let mut out = vec![0u8; 64 * 1024];
    let mut out_len = 0i32;
    let code = unsafe {
        cpp_invoke(
            handle,
            tags::CAPABILITIES,
            request.as_ptr(),
            request.len() as i32,
            out.as_mut_ptr(),
            out.len() as i32,
            &mut out_len,
        )
    };
    assert_eq!(code, 0);
    out.truncate(out_len as usize);
    assert_eq!(out[0], 0, "success envelope");
    let mut reader = WireReader::new(&out[1..]).unwrap();
    let capabilities = Capabilities::read(&mut reader).unwrap();
    reader.finish().unwrap();
    assert!(capabilities.supported.contains(&Capability::SEARCH.to_string()));
    cpp_close(handle);
}

#[test]
fn small_buffers_report_required_capacity_and_retry_cleanly() {
    let handle = create_session();
    let request = encode(&SearchRequest { keyword: "x".into(), limit: 30, offset: 0 }, SearchRequest::write);
    let mut out = vec![0u8; 4];
    let mut out_len = 0i32;
    let code = unsafe {
        cpp_invoke(
            handle,
            tags::SEARCH,
            request.as_ptr(),
            request.len() as i32,
            out.as_mut_ptr(),
            out.len() as i32,
            &mut out_len,
        )
    };
    assert_eq!(code, 1, "buffer too small");
    assert!(out_len > 4);

    let mut big = vec![0u8; out_len as usize];
    let mut retry_len = 0i32;
    let code = unsafe {
        cpp_invoke(
            handle,
            tags::SEARCH,
            request.as_ptr(),
            request.len() as i32,
            big.as_mut_ptr(),
            big.len() as i32,
            &mut retry_len,
        )
    };
    assert_eq!(code, 0, "retry is idempotent");
    cpp_close(handle);
}

#[test]
fn undeclared_capability_returns_the_unsupported_envelope() {
    let handle = create_session();
    let request = encode(&IdRequest { id: "x".into() }, IdRequest::write);
    let mut out = vec![0u8; 4096];
    let mut out_len = 0i32;
    let code = unsafe {
        cpp_invoke(
            handle,
            tags::GET_LYRICS,
            request.as_ptr(),
            request.len() as i32,
            out.as_mut_ptr(),
            out.len() as i32,
            &mut out_len,
        )
    };
    assert_eq!(code, 0, "business errors are not ABI errors");
    out.truncate(out_len as usize);
    assert_eq!(out[0], 1, "failure envelope");
    let mut reader = WireReader::new(&out[1..]).unwrap();
    let failure = WireFailure::read(&mut reader).unwrap();
    assert_eq!(failure.category, FailureCategory::Unsupported.as_str());
    cpp_close(handle);
}

#[test]
fn cancel_and_double_close_are_safe() {
    // Tests share the process-wide session registry, so compare against a baseline.
    let handle = create_session();
    cpp_cancel(handle);
    cpp_close(handle);
    cpp_close(handle);
    // Other tests in this binary hold sessions concurrently; only assert that
    // this handle no longer dispatches.
    let mut out = vec![0u8; 64];
    let mut out_len = 0i32;
    let code = unsafe {
        cpp_invoke(handle, tags::CAPABILITIES, std::ptr::null(), 0, out.as_mut_ptr(), out.len() as i32, &mut out_len)
    };
    assert_eq!(code, 2);
}

#[test]
fn closed_handles_do_not_dispatch() {
    let handle = create_session();
    cpp_close(handle);
    let mut out = vec![0u8; 64];
    let mut out_len = 0i32;
    let code = unsafe {
        cpp_invoke(handle, tags::CAPABILITIES, std::ptr::null(), 0, out.as_mut_ptr(), out.len() as i32, &mut out_len)
    };
    assert_eq!(code, 2, "ABI failure, never a panic");
}
