use sha2::{Digest, Sha256};

pub const MIN_PREFIX_HEX_LENGTH: usize = 16;
pub const FULL_SHA256_HEX_LENGTH: usize = 64;

/// Computes the lowercase hex-encoded SHA-256 digest of DER-encoded certificate bytes.
pub fn sha256_hex(bytes: &[u8]) -> String {
    let digest = Sha256::digest(bytes);
    let mut out = String::with_capacity(FULL_SHA256_HEX_LENGTH);
    for b in digest {
        use std::fmt::Write;
        let _ = write!(&mut out, "{b:02x}");
    }
    out
}

/// Normalizes a certificate fingerprint or mDNS `fp` prefix into lowercase hex.
/// Returns an empty string if any non-hex, non-separator character is encountered.
pub fn normalize(raw: &str) -> String {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return String::new();
    }
    let stripped = trimmed
        .strip_prefix("sha256:")
        .or_else(|| trimmed.strip_prefix("SHA256:"))
        .unwrap_or(trimmed);

    let mut out = String::with_capacity(stripped.len());
    for ch in stripped.chars() {
        if ch.is_ascii_hexdigit() {
            out.push(ch.to_ascii_lowercase());
        } else if ch == ':' || ch == '-' || ch.is_ascii_whitespace() {
            continue;
        } else {
            return String::new();
        }
    }
    out
}

/// Verifies `actual_full_hex` (64-char SHA-256 leaf cert digest) against `expected_pin`
/// (either full 64-char digest or >=16-char mDNS TXT `fp` prefix, matching macOS client rules).
pub fn matches(actual_full_hex: &str, expected_pin: &str) -> bool {
    let actual = normalize(actual_full_hex);
    let expected = normalize(expected_pin);
    if actual.len() != FULL_SHA256_HEX_LENGTH || expected.len() < MIN_PREFIX_HEX_LENGTH {
        return false;
    }
    if expected.len() == FULL_SHA256_HEX_LENGTH {
        actual == expected
    } else if expected.len() < FULL_SHA256_HEX_LENGTH {
        actual.starts_with(&expected)
    } else {
        false
    }
}

/// Formats the leading 64 bits (16 hex chars) as `XXXX-XXXX-XXXX-XXXX` for human TOFU verification.
pub fn format_short(raw: &str) -> String {
    let norm = normalize(raw);
    if norm.is_empty() {
        return String::new();
    }
    let take_len = norm.len().min(16);
    let upper = norm[..take_len].to_ascii_uppercase();
    upper
        .as_bytes()
        .chunks(4)
        .map(|chunk| std::str::from_utf8(chunk).unwrap_or(""))
        .collect::<Vec<_>>()
        .join("-")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sha256_and_short_format_roundtrip() {
        let digest = sha256_hex(b"opaldesk-test-cert");
        assert_eq!(digest.len(), FULL_SHA256_HEX_LENGTH);
        let short = format_short(&digest);
        assert_eq!(short.len(), 19); // XXXX-XXXX-XXXX-XXXX
        assert!(matches(&digest, &short));
        assert!(matches(&digest, &digest));
    }

    #[test]
    fn rejects_short_or_malformed_pins() {
        let digest = sha256_hex(b"opaldesk-leaf-cert");
        assert!(!matches(&digest, ""));
        assert!(!matches(&digest, &digest[..15]));
        assert!(!matches(&digest, "zzzz-zzzz-zzzz-zzzz"));
        assert_eq!(normalize("AA:BB:GG:11"), "");
    }
}
