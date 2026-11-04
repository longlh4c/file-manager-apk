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
