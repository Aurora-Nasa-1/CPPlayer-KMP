//! Bounded positional codec for the CPPlayer `kmp-sdk-v1` wire format.
//!
//! Rules mirrored from `provider-sdk-kmp/src/commonMain/kotlin/cp/player/sdk/Wire.kt`:
//! little-endian, fixed width, no alignment padding, byte lengths for strings,
//! and hard failure (never silent truncation) when a limit is exceeded.

use std::fmt;

pub const MAX_BYTES: usize = 4 * 1024 * 1024;
pub const MAX_ITEMS: usize = 500;
pub const MAX_SAFE_INTEGER: i64 = 9_007_199_254_740_991;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum WireError {
    Truncated,
    TrailingData,
    InvalidBoolean,
    InvalidUtf8,
    ByteLimit,
    ItemLimit,
    InvalidLength,
}

impl fmt::Display for WireError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let text = match self {
            WireError::Truncated => "Truncated wire value",
            WireError::TrailingData => "Trailing wire data",
            WireError::InvalidBoolean => "Invalid wire boolean",
            WireError::InvalidUtf8 => "Invalid UTF-8 in wire string",
            WireError::ByteLimit => "Wire byte limit",
            WireError::ItemLimit => "Wire item limit",
            WireError::InvalidLength => "Invalid wire length",
        };
        f.write_str(text)
    }
}

impl std::error::Error for WireError {}

pub type WireResult<T> = Result<T, WireError>;

pub struct WireWriter {
    out: Vec<u8>,
}

impl Default for WireWriter {
    fn default() -> Self {
        Self::new()
    }
}

impl WireWriter {
    pub fn new() -> Self {
        Self { out: Vec::with_capacity(256) }
    }

    pub fn with_capacity(capacity: usize) -> Self {
        Self { out: Vec::with_capacity(capacity.min(MAX_BYTES)) }
    }

    fn reserve(&mut self, count: usize) -> WireResult<()> {
        if count > MAX_BYTES - self.out.len() {
            return Err(WireError::ByteLimit);
        }
        self.out.reserve(count);
        Ok(())
    }

    pub fn int(&mut self, value: i32) -> WireResult<()> {
        self.reserve(4)?;
        self.out.extend_from_slice(&value.to_le_bytes());
        Ok(())
    }

    pub fn long(&mut self, value: i64) -> WireResult<()> {
        self.reserve(8)?;
        self.out.extend_from_slice(&value.to_le_bytes());
        Ok(())
    }

    pub fn bool(&mut self, value: bool) -> WireResult<()> {
        self.reserve(1)?;
        self.out.push(u8::from(value));
        Ok(())
    }

    pub fn bytes(&mut self, value: &[u8]) -> WireResult<()> {
        if value.len() > MAX_BYTES {
            return Err(WireError::ByteLimit);
        }
        self.int(value.len() as i32)?;
        self.reserve(value.len())?;
        self.out.extend_from_slice(value);
        Ok(())
    }

    pub fn string(&mut self, value: &str) -> WireResult<()> {
        self.bytes(value.as_bytes())
    }

    pub fn optional<T: ?Sized, F>(&mut self, value: Option<&T>, mut write: F) -> WireResult<()>
    where
        F: FnMut(&mut Self, &T) -> WireResult<()>,
    {
        self.bool(value.is_some())?;
        match value {
            Some(item) => write(self, item),
            None => Ok(()),
        }
    }

    pub fn list<T, F>(&mut self, value: &[T], mut write: F) -> WireResult<()>
    where
        F: FnMut(&mut Self, &T) -> WireResult<()>,
    {
        if value.len() > MAX_ITEMS {
            return Err(WireError::ItemLimit);
        }
        self.int(value.len() as i32)?;
        for item in value {
            write(self, item)?;
        }
        Ok(())
    }

    pub fn finish(self) -> Vec<u8> {
        self.out
    }
}

pub struct WireReader<'a> {
    input: &'a [u8],
    offset: usize,
}

impl<'a> WireReader<'a> {
    pub fn new(input: &'a [u8]) -> WireResult<Self> {
        if input.len() > MAX_BYTES {
            return Err(WireError::ByteLimit);
        }
        Ok(Self { input, offset: 0 })
    }

    fn next(&mut self) -> WireResult<u8> {
        let byte = *self.input.get(self.offset).ok_or(WireError::Truncated)?;
        self.offset += 1;
        Ok(byte)
    }

    pub fn int(&mut self) -> WireResult<i32> {
        let slice = self.input.get(self.offset..self.offset + 4).ok_or(WireError::Truncated)?;
        self.offset += 4;
        Ok(i32::from_le_bytes(slice.try_into().expect("slice length checked")))
    }

    pub fn long(&mut self) -> WireResult<i64> {
        let slice = self.input.get(self.offset..self.offset + 8).ok_or(WireError::Truncated)?;
        self.offset += 8;
        Ok(i64::from_le_bytes(slice.try_into().expect("slice length checked")))
    }

    pub fn bool(&mut self) -> WireResult<bool> {
        match self.next()? {
            0 => Ok(false),
            1 => Ok(true),
            _ => Err(WireError::InvalidBoolean),
        }
    }

    pub fn bytes(&mut self) -> WireResult<Vec<u8>> {
        let length = self.int()?;
        if length < 0 {
            return Err(WireError::InvalidLength);
        }
        let length = length as usize;
        let end = self.offset.checked_add(length).ok_or(WireError::InvalidLength)?;
        let slice = self.input.get(self.offset..end).ok_or(WireError::InvalidLength)?;
        self.offset = end;
        Ok(slice.to_vec())
    }

    pub fn string(&mut self) -> WireResult<String> {
        String::from_utf8(self.bytes()?).map_err(|_| WireError::InvalidUtf8)
    }

    pub fn optional<T, F>(&mut self, mut read: F) -> WireResult<Option<T>>
    where
        F: FnMut(&mut Self) -> WireResult<T>,
    {
        if self.bool()? {
            read(self).map(Some)
        } else {
            Ok(None)
        }
    }

    pub fn list<T, F>(&mut self, mut read: F) -> WireResult<Vec<T>>
    where
        F: FnMut(&mut Self) -> WireResult<T>,
    {
        let count = self.int()?;
        if count < 0 || count as usize > MAX_ITEMS {
            return Err(WireError::ItemLimit);
        }
        let mut items = Vec::with_capacity(count as usize);
        for _ in 0..count {
            items.push(read(self)?);
        }
        Ok(items)
    }

    pub fn finish(&self) -> WireResult<()> {
        if self.offset == self.input.len() {
            Ok(())
        } else {
            Err(WireError::TrailingData)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn primitives_round_trip() {
        let mut writer = WireWriter::new();
        writer.int(-7).unwrap();
        writer.long(MAX_SAFE_INTEGER).unwrap();
        writer.bool(true).unwrap();
        writer.string("音源").unwrap();
        writer.bytes(&[0, 127, 128, 255]).unwrap();
        let bytes = writer.finish();

        let mut reader = WireReader::new(&bytes).unwrap();
        assert_eq!(reader.int().unwrap(), -7);
        assert_eq!(reader.long().unwrap(), MAX_SAFE_INTEGER);
        assert!(reader.bool().unwrap());
        assert_eq!(reader.string().unwrap(), "音源");
        assert_eq!(reader.bytes().unwrap(), vec![0, 127, 128, 255]);
        reader.finish().unwrap();
    }

    #[test]
    fn limits_fail_instead_of_truncating() {
        let mut writer = WireWriter::new();
        assert_eq!(writer.list(&vec![0u8; MAX_ITEMS + 1], |w, _| w.int(0)), Err(WireError::ItemLimit));
        let mut writer = WireWriter::new();
        assert_eq!(writer.bytes(&vec![0u8; MAX_BYTES + 1]), Err(WireError::ByteLimit));
        // The length prefix counts against the same budget, so a payload equal
        // to MAX_BYTES cannot fit — same rule as the Kotlin writer.
        let mut writer = WireWriter::new();
        assert_eq!(writer.bytes(&vec![0u8; MAX_BYTES]), Err(WireError::ByteLimit));
        let mut writer = WireWriter::new();
        writer.bytes(&vec![0u8; MAX_BYTES - 4]).unwrap();
        assert_eq!(writer.int(0), Err(WireError::ByteLimit));
    }

    #[test]
    fn malformed_input_is_rejected() {
        assert_eq!(WireReader::new(&[]).unwrap().int(), Err(WireError::Truncated));
        assert_eq!(WireReader::new(&[2]).unwrap().bool(), Err(WireError::InvalidBoolean));
        assert_eq!(WireReader::new(&[0]).unwrap().finish(), Err(WireError::TrailingData));
        assert_eq!(WireReader::new(&[1, 0, 0, 0, 0xff]).unwrap().string(), Err(WireError::InvalidUtf8));
        assert_eq!(WireReader::new(&[0xff, 0xff, 0xff, 0xff]).unwrap().list(|r| r.int()), Err(WireError::ItemLimit));
        assert_eq!(WireReader::new(&[2, 0, 0, 0, 1]).unwrap().bytes(), Err(WireError::InvalidLength));
        assert_eq!(WireReader::new(&[0xff, 0xff, 0xff, 0x7f, 1]).unwrap().bytes(), Err(WireError::InvalidLength));
        assert!(matches!(
            WireReader::new(&vec![0u8; MAX_BYTES + 1]),
            Err(WireError::ByteLimit)
        ));
    }
}
