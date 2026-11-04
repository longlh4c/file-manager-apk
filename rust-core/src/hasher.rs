use std::fs::File;
use std::io::{self, Read};
use std::path::Path;

use blake3::Hasher as Blake3Hasher;
use crc32fast::Hasher as Crc32Hasher;
use md5::{Digest as Md5Digest, Md5};
use sha1::Sha1;
use sha2::Sha256;
use serde::{Deserialize, Serialize};

pub const FLAG_MD5: u32 = 1 << 0;
pub const FLAG_SHA1: u32 = 1 << 1;
pub const FLAG_SHA256: u32 = 1 << 2;
pub const FLAG_CRC32: u32 = 1 << 3;
pub const FLAG_BLAKE3: u32 = 1 << 4;

#[derive(Serialize, Deserialize, Debug, Default)]
pub struct ChecksumResult {
    pub md5: Option<String>,
    pub sha1: Option<String>,
    pub sha256: Option<String>,
    pub crc32: Option<String>,
    pub blake3: Option<String>,
}

pub fn compute_checksums(path_str: &str, flags: u32) -> io::Result<ChecksumResult> {
    let path = Path::new(path_str);
    let mut file = File::open(path)?;

    let mut md5_opt = if (flags & FLAG_MD5) != 0 { Some(Md5::new()) } else { None };
    let mut sha1_opt = if (flags & FLAG_SHA1) != 0 { Some(Sha1::new()) } else { None };
    let mut sha256_opt = if (flags & FLAG_SHA256) != 0 { Some(Sha256::new()) } else { None };
    let mut crc32_opt = if (flags & FLAG_CRC32) != 0 { Some(Crc32Hasher::new()) } else { None };
    let mut blake3_opt = if (flags & FLAG_BLAKE3) != 0 { Some(Blake3Hasher::new()) } else { None };

    let mut buffer = [0u8; 64 * 1024];

    loop {
        let count = file.read(&mut buffer)?;
        if count == 0 {
            break;
        }
        let chunk = &buffer[..count];

        if let Some(ref mut h) = md5_opt {
            h.update(chunk);
        }
        if let Some(ref mut h) = sha1_opt {
            h.update(chunk);
        }
        if let Some(ref mut h) = sha256_opt {
            h.update(chunk);
        }
        if let Some(ref mut h) = crc32_opt {
            h.update(chunk);
        }
        if let Some(ref mut h) = blake3_opt {
            h.update(chunk);
        }
    }

    Ok(ChecksumResult {
        md5: md5_opt.map(|h| format!("{:x}", h.finalize())),
        sha1: sha1_opt.map(|h| format!("{:x}", h.finalize())),
        sha256: sha256_opt.map(|h| format!("{:x}", h.finalize())),
        crc32: crc32_opt.map(|h| format!("{:08x}", h.finalize())),
        blake3: blake3_opt.map(|h| h.finalize().to_hex().to_string()),
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    #[test]
    fn test_compute_checksums() {
        let temp_dir = std::env::temp_dir();
        let test_file = temp_dir.join("test_hasher.txt");
        {
            let mut f = File::create(&test_file).unwrap();
            f.write_all(b"hello world").unwrap();
        }

        let flags = FLAG_MD5 | FLAG_SHA1 | FLAG_SHA256 | FLAG_CRC32 | FLAG_BLAKE3;
        let res = compute_checksums(test_file.to_str().unwrap(), flags).unwrap();

        assert_eq!(res.md5.as_deref(), Some("5eb63bbbe01eeed093cb22bb8f5acdc3"));
        assert_eq!(res.sha1.as_deref(), Some("2aae6c35c94fcfb415dbe95f408b9ce91ee846ed"));
        assert_eq!(res.sha256.as_deref(), Some("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"));
        assert_eq!(res.crc32.as_deref(), Some("0d4a1185"));
        assert_eq!(res.blake3.as_deref(), Some(blake3::hash(b"hello world").to_hex().as_str()));

        let _ = std::fs::remove_file(test_file);
    }
}

