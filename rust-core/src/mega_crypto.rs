use aes::cipher::{
    generic_array::GenericArray, BlockDecrypt, BlockDecryptMut, BlockEncrypt, KeyInit, KeyIvInit,
    StreamCipher, StreamCipherSeek,
};
use aes::Aes128;
use base64::Engine as _;
use rayon::prelude::*;
use serde::{Deserialize, Serialize};

type Aes128CbcDec = cbc::Decryptor<Aes128>;
type Aes128Ctr = ctr::Ctr128BE<Aes128>;

/// Computes MEGA's chunked CBC-MAC for upload integrity.
pub struct MegaChunkedMac {
    cipher: Aes128,
    nonce8: [u8; 8],
    mac: [u8; 16],
    macs: Vec<[u8; 16]>,
    pos: u64,
    pos_next: u64,
    increment: u64,
}

impl MegaChunkedMac {
    pub fn new(aes_key: &[u8], nonce: &[u8]) -> Self {
        let key_arr = GenericArray::from_slice(&aes_key[..16]);
        let cipher = Aes128::new(key_arr);
        let mut nonce8 = [0u8; 8];
        nonce8.copy_from_slice(&nonce[..8]);

        let mut mac = [0u8; 16];
        mac[0..8].copy_from_slice(&nonce8);
        mac[8..16].copy_from_slice(&nonce8);

        Self {
            cipher,
            nonce8,
            mac,
            macs: Vec::new(),
            pos: 0,
            pos_next: 131072,
            increment: 131072,
        }
    }

    fn fresh_mac(&self) -> [u8; 16] {
        let mut m = [0u8; 16];
        m[0..8].copy_from_slice(&self.nonce8);
        m[8..16].copy_from_slice(&self.nonce8);
        m
    }

    pub fn update(&mut self, buffer: &[u8]) {
        let mut i = 0;
        while i < buffer.len() {
            for j in 0..16 {
                let b = if i + j < buffer.len() { buffer[i + j] } else { 0 };
                self.mac[j] ^= b;
            }

            let block = GenericArray::from_mut_slice(&mut self.mac);
            self.cipher.encrypt_block(block);

            self.check_bounding();
            i += 16;
        }
    }

    fn check_bounding(&mut self) {
        self.pos += 16;
        if self.pos >= self.pos_next {
            self.macs.push(self.mac);
            self.mac = self.fresh_mac();
            if self.increment < 1048576 {
                self.increment += 131072;
            }
            self.pos_next += self.increment;
        }
    }

    pub fn condense(mut self) -> [u8; 8] {
        self.macs.push(self.mac);

        let mut result = [0u8; 16];
        for item in &self.macs {
            for j in 0..16 {
                result[j] ^= item[j];
            }
            let block = GenericArray::from_mut_slice(&mut result);
            self.cipher.encrypt_block(block);
        }

        let a = u32::from_be_bytes([result[0], result[1], result[2], result[3]])
            ^ u32::from_be_bytes([result[4], result[5], result[6], result[7]]);
        let b = u32::from_be_bytes([result[8], result[9], result[10], result[11]])
            ^ u32::from_be_bytes([result[12], result[13], result[14], result[15]]);

        let mut out = [0u8; 8];
        out[0..4].copy_from_slice(&a.to_be_bytes());
        out[4..8].copy_from_slice(&b.to_be_bytes());
        out
    }
}

/// Streaming AES-128-CTR in-place decryptor for arbitrary byte offset slices.
pub fn mega_ctr_decrypt_slice(data: &mut [u8], aes_key: &[u8], nonce: &[u8], start_offset: u64) {
    if data.is_empty() {
        return;
    }
    let key = GenericArray::from_slice(&aes_key[..16]);
    let mut iv = [0u8; 16];
    iv[0..8].copy_from_slice(&nonce[..8]);
    // The low 8 bytes are the counter in big-endian, starting at 0
    let iv_arr = GenericArray::from_slice(&iv);

    let mut cipher = Aes128Ctr::new(key, iv_arr);
    cipher.seek(start_offset);
    cipher.apply_keystream(data);
}

fn base64_url_decode(s: &str) -> Option<Vec<u8>> {
    let clean = s.trim().replace('-', "+").replace('_', "/");
    let padded = match clean.len() % 4 {
        2 => format!("{}==", clean),
        3 => format!("{}=", clean),
        _ => clean,
    };
    base64::engine::general_purpose::STANDARD.decode(padded).ok()
}

/// Derives a 16-byte node key by decrypting `cleanKey` with `master_key` and folding if necessary.
pub fn derive_node_key_bytes(key_str: &str, master_key: &[u8]) -> Option<[u8; 16]> {
    let first_key = key_str.split('/').find(|k| !k.trim().is_empty())?;
    let clean_key = if let Some((_, after)) = first_key.split_once(':') {
        after
    } else {
        first_key
    };

    let mut enc_node_key = base64_url_decode(clean_key)?;
    if enc_node_key.is_empty() || enc_node_key.len() % 16 != 0 {
        return None;
    }

    let key = GenericArray::from_slice(&master_key[..16]);
    let cipher = Aes128::new(key);
    for chunk in enc_node_key.chunks_exact_mut(16) {
        let block = GenericArray::from_mut_slice(chunk);
        cipher.decrypt_block(block);
    }

    if enc_node_key.len() >= 32 {
        let mut folded = [0u8; 16];
        for i in 0..16 {
            folded[i] = enc_node_key[i] ^ enc_node_key[16 + i];
        }
        Some(folded)
    } else {
        let mut key16 = [0u8; 16];
        key16.copy_from_slice(&enc_node_key[..16]);
        Some(key16)
    }
}

#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct DecryptedNodeAttributes {
    pub name: String,
    pub mtime_ms: Option<i64>,
}

pub fn parse_mtime_from_fingerprint(fingerprint_b64: &str) -> Option<i64> {
    let bytes = base64_url_decode(fingerprint_b64)?;
    if bytes.len() < 17 {
        return None;
    }
    let len = bytes[16] as usize;
    if len == 0 || len > 8 || bytes.len() < 17 + len {
        return None;
    }
    let mut mtime_sec = 0i64;
    for i in 0..len {
        mtime_sec |= (bytes[17 + i] as i64 & 0xFF) << (i * 8);
    }
    if mtime_sec > 0 {
        Some(mtime_sec)
    } else {
        None
    }
}

/// Decrypts node attributes JSON string and parses the filename and preserved mtime.
pub fn decrypt_node_attributes(
    attr_enc_str: &str,
    key_str: &str,
    master_key: Option<&[u8]>,
) -> Option<DecryptedNodeAttributes> {
    fn parse_json_attr(s: &str) -> Option<DecryptedNodeAttributes> {
        let start = s.find('{')?;
        let end = s.rfind('}')?;
        if end <= start {
            return None;
        }
        let json_str = &s[start..=end];
        let val: serde_json::Value = serde_json::from_str(json_str).ok()?;
        let name = val.get("n")?.as_str()?.to_string();
        if name.is_empty() {
            return None;
        }

        let mtime_sec = if let Some(mtime_val) = val.get("mtime").and_then(|v| v.as_i64()) {
            if mtime_val > 0 {
                Some(mtime_val)
            } else {
                val.get("c")
                    .and_then(|c| c.as_str())
                    .and_then(parse_mtime_from_fingerprint)
            }
        } else {
            val.get("c")
                .and_then(|c| c.as_str())
                .and_then(parse_mtime_from_fingerprint)
        };

        Some(DecryptedNodeAttributes {
            name,
            mtime_ms: mtime_sec.map(|s| s * 1000),
        })
    }

    if attr_enc_str.starts_with('{') {
        return parse_json_attr(attr_enc_str);
    }

    let enc_bytes = base64_url_decode(attr_enc_str)?;
    if let (Some(mk), false) = (master_key, key_str.is_empty()) {
        if let Some(file_key_bytes) = derive_node_key_bytes(key_str, mk) {
            let iv = [0u8; 16];
            if enc_bytes.len() % 16 == 0 {
                if let Ok(mut decryptor) = Aes128CbcDec::new_from_slices(&file_key_bytes, &iv) {
                    let mut buf = enc_bytes.clone();
                    for chunk in buf.chunks_exact_mut(16) {
                        let block = GenericArray::from_mut_slice(chunk);
                        decryptor.decrypt_block_mut(block);
                    }
                    if let Ok(s) = std::str::from_utf8(&buf) {
                        let trimmed = s.trim_matches(|c| c == '\0' || c == ' ');
                        if let Some(attrs) = parse_json_attr(trimmed) {
                            return Some(attrs);
                        }
                    }
                }
            }
        }
    }

    // Fallback: try raw decode
    if let Ok(raw) = std::str::from_utf8(&enc_bytes) {
        if let Some(attrs) = parse_json_attr(raw) {
            return Some(attrs);
        }
    }

    None
}

#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct RawMegaNodeInput {
    pub h: String,
    pub p: String,
    pub t: i32,
    pub s: i64,
    pub ts: i64,
    pub a: String,
    pub k: String,
    pub fa: Option<String>,
}

#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct DecryptedMegaNodeOutput {
    pub handle: String,
    pub parent_handle: String,
    pub node_type: i32,
    pub size: i64,
    pub timestamp: i64,
    pub name: String,
    pub key_str: String,
    pub file_attr_str: String,
}

/// Parallel batch decryption of thousands of MEGA nodes using Rayon.
pub fn batch_decrypt_mega_nodes(
    nodes: &[RawMegaNodeInput],
    master_key: &[u8],
) -> Vec<DecryptedMegaNodeOutput> {
    nodes
        .par_iter()
        .map(|node| {
            let mut name = if node.t == 1 || node.t == 2 {
                format!("Folder_{}", node.h)
            } else {
                format!("File_{}", node.h)
            };
            let mut effective_ts = node.ts * 1000;

            if !node.a.is_empty() {
                if let Some(attrs) = decrypt_node_attributes(&node.a, &node.k, Some(master_key)) {
                    name = attrs.name;
                    if let Some(mtime) = attrs.mtime_ms {
                        effective_ts = mtime;
                    }
                }
            }

            DecryptedMegaNodeOutput {
                handle: node.h.clone(),
                parent_handle: node.p.clone(),
                node_type: node.t,
                size: node.s,
                timestamp: effective_ts,
                name,
                key_str: node.k.clone(),
                file_attr_str: node.fa.clone().unwrap_or_default(),
            }
        })
        .collect()
}

pub fn decrypt_mega_thumbnail(encrypted_data: &[u8], node_key: &[u8]) -> Result<Vec<u8>, String> {
    if node_key.len() < 16 {
        return Err("Node key must be at least 16 bytes".to_string());
    }
    if encrypted_data.len() % 16 != 0 || encrypted_data.is_empty() {
        return Err("Encrypted data length must be non-empty and multiple of 16".to_string());
    }

    let iv = [0u8; 16];
    let mut decryptor = Aes128CbcDec::new_from_slices(&node_key[..16], &iv)
        .map_err(|e| format!("Invalid key/iv: {}", e))?;

    let mut buf = encrypted_data.to_vec();
    for chunk in buf.chunks_exact_mut(16) {
        let block = GenericArray::from_mut_slice(chunk);
        decryptor.decrypt_block_mut(block);
    }

    if let Some(pos) = buf.windows(2).rposition(|w| w == [0xFF, 0xD9]) {
        buf.truncate(pos + 2);
    }

    Ok(buf)
}

#[cfg(test)]
mod tests {
    use super::*;
    use cipher::BlockEncryptMut;

    #[test]
    fn test_mega_chunked_mac() {
        let key = [1u8; 16];
        let nonce = [2u8; 8];
        let mut mac = MegaChunkedMac::new(&key, &nonce);
        let data = vec![42u8; 300_000];
        mac.update(&data);
        let result = mac.condense();
        assert_eq!(result.len(), 8);
        assert_ne!(result, [0u8; 8]);
    }

    #[test]
    fn test_mega_ctr_decrypt_slice() {
        let key = [5u8; 16];
        let nonce = [7u8; 8];
        let mut data = vec![99u8; 1024];
        let original = data.clone();

        // Encrypt (CTR is symmetric)
        mega_ctr_decrypt_slice(&mut data, &key, &nonce, 0);
        assert_ne!(data, original);

        // Decrypt
        mega_ctr_decrypt_slice(&mut data, &key, &nonce, 0);
        assert_eq!(data, original);
    }

    #[test]
    fn test_decrypt_mega_thumbnail() {
        let key = [42u8; 16];
        let iv = [0u8; 16];
        let mut jpeg = vec![0xFF, 0xD8, 0x01, 0x02, 0x03, 0xFF, 0xD9];
        while jpeg.len() % 16 != 0 {
            jpeg.push(0);
        }
        let mut enc = jpeg.clone();
        let mut encryptor = cbc::Encryptor::<aes::Aes128>::new_from_slices(&key, &iv).unwrap();
        for chunk in enc.chunks_exact_mut(16) {
            let block = cipher::generic_array::GenericArray::from_mut_slice(chunk);
            encryptor.encrypt_block_mut(block);
        }
        let decrypted = decrypt_mega_thumbnail(&enc, &key).unwrap();
        assert_eq!(decrypted, vec![0xFF, 0xD8, 0x01, 0x02, 0x03, 0xFF, 0xD9]);
    }
}
