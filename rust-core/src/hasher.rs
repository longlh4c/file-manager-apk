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

/// Computes block MD5 digests for TeraBox precreate / rapid-upload.
pub fn compute_block_md5s(path_str: &str, block_size: usize) -> io::Result<Vec<String>> {
    let path = Path::new(path_str);
    let mut file = File::open(path)?;
    let mut result = Vec::new();
    let mut buffer = vec![0u8; 64 * 1024];

    loop {
        let mut hasher = Md5::new();
        let mut in_block = 0usize;

        while in_block < block_size {
            let to_read = (block_size - in_block).min(buffer.len());
            let count = file.read(&mut buffer[..to_read])?;
            if count == 0 {
                break;
            }
            hasher.update(&buffer[..count]);
            in_block += count;
        }

        if in_block == 0 && !result.is_empty() {
            break;
        }

        result.push(format!("{:x}", hasher.finalize()));

        if in_block < block_size {
            break;
        }
    }

    if result.is_empty() {
        result.push("d41d8cd98f00b204e9800998ecf8427e".to_string());
    }

    Ok(result)
}

/// Computes Dropbox 4MB chunked double-SHA256 content hash.
pub fn compute_dropbox_content_hash(path_str: &str) -> io::Result<String> {
    let path = Path::new(path_str);
    let mut file = File::open(path)?;
    const DROPBOX_BLOCK_SIZE: usize = 4 * 1024 * 1024; // 4MB

    let mut block_hashes: Vec<u8> = Vec::new();
    let mut buffer = vec![0u8; 64 * 1024];

    loop {
        let mut hasher = Sha256::new();
        let mut in_block = 0usize;

        while in_block < DROPBOX_BLOCK_SIZE {
            let to_read = (DROPBOX_BLOCK_SIZE - in_block).min(buffer.len());
            let count = file.read(&mut buffer[..to_read])?;
            if count == 0 {
                break;
            }
            hasher.update(&buffer[..count]);
            in_block += count;
        }

        if in_block == 0 {
            if block_hashes.is_empty() {
                let empty_hash = Sha256::digest(b"");
                return Ok(format!("{:x}", empty_hash));
            }
            break;
        }

        block_hashes.extend_from_slice(&hasher.finalize());

        if in_block < DROPBOX_BLOCK_SIZE {
            break;
        }
    }

    let final_hash = Sha256::digest(&block_hashes);
    Ok(format!("{:x}", final_hash))
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

    #[test]
    fn test_compute_block_md5s() {
        let temp_dir = std::env::temp_dir();
        let test_file = temp_dir.join("test_blocks.txt");
        {
            let mut f = File::create(&test_file).unwrap();
            f.write_all(b"1234567890abcdef").unwrap();
        }

        // Test with 8-byte block size -> 2 blocks of 8 bytes
        let blocks = compute_block_md5s(test_file.to_str().unwrap(), 8).unwrap();
        assert_eq!(blocks.len(), 2);
        assert_eq!(blocks[0], "25d55ad283aa400af464c76d713c07ad"); // md5("12345678")
        assert_eq!(blocks[1], "11437de811a8e37416c7fbbb9d0d433e"); // md5("90abcdef")

        let _ = std::fs::remove_file(test_file);
    }

    #[test]
    fn test_compute_dropbox_content_hash() {
        let temp_dir = std::env::temp_dir();
        let test_file = temp_dir.join("test_dropbox_hash.txt");
        {
            let mut f = File::create(&test_file).unwrap();
            f.write_all(b"dropbox test content").unwrap();
        }

        let hash = compute_dropbox_content_hash(test_file.to_str().unwrap()).unwrap();
        // Since content < 4MB, it's SHA256(SHA256("dropbox test content"))
        let first_sha = Sha256::digest(b"dropbox test content");
        let expected = format!("{:x}", Sha256::digest(&first_sha));
        assert_eq!(hash, expected);

        let _ = std::fs::remove_file(test_file);
    }
}

