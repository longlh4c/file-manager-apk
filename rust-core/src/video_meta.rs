use std::fs::File;
use std::io::{self, Read};
use std::path::Path;
use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Debug, Default)]
pub struct VideoMetadata {
    pub duration_ms: u64,
    pub width: u32,
    pub height: u32,
    pub quality_badge: String,
    pub format: String,
}

pub fn extract_video_poster(path_str: &str) -> io::Result<Option<Vec<u8>>> {
    let path = Path::new(path_str);
    let mut file = match File::open(path) {
        Ok(f) => f,
        Err(_) => return Ok(None),
    };
    let ext = path
        .extension()
        .and_then(|s| s.to_str())
        .unwrap_or("")
        .to_ascii_lowercase();

    match ext.as_str() {
        "mp4" | "mov" | "m4v" => extract_mp4_poster(&mut file),
        "mkv" | "webm" => extract_mkv_poster(&mut file),
        _ => Ok(None),
    }
}

pub fn parse_video_metadata(path_str: &str) -> io::Result<VideoMetadata> {
    let path = Path::new(path_str);
    let mut file = File::open(path)?;
    let ext = path
        .extension()
        .and_then(|s| s.to_str())
        .unwrap_or("")
        .to_ascii_lowercase();

    match ext.as_str() {
        "mp4" | "mov" | "m4v" | "3gp" => parse_mp4_metadata(&mut file),
        "mkv" | "webm" => parse_mkv_metadata(&mut file),
        _ => Ok(VideoMetadata::default()),
    }
}

// -----------------------------------------------------------------------------
// MP4 / MOV Parser
// -----------------------------------------------------------------------------

fn extract_mp4_poster(file: &mut File) -> io::Result<Option<Vec<u8>>> {
    // Scan up to 5MB of file for moov/udta/meta/ilst/covr
    let mut buffer = vec![0u8; 5 * 1024 * 1024];
    let n = file.read(&mut buffer)?;
    let slice = &buffer[..n];

    if let Some(pos) = find_subsequence(slice, b"covr") {
        let p = pos + 4;
        if p + 16 <= slice.len() {
            // [size: 4B] "data" [type: 4B] [locale: 4B] [bytes...]
            if &slice[p + 4..p + 8] == b"data" {
                let data_size = u32::from_be_bytes([slice[p], slice[p + 1], slice[p + 2], slice[p + 3]]) as usize;
                let data_offset = p + 16;
                let payload_len = data_size.saturating_sub(16);
                if data_offset + payload_len <= slice.len() {
                    let bytes = slice[data_offset..data_offset + payload_len].to_vec();
                    // Validate JPEG or PNG header
                    if bytes.starts_with(&[0xFF, 0xD8]) || bytes.starts_with(&[0x89, b'P', b'N', b'G']) {
                        return Ok(Some(bytes));
                    }
                }
            }
        }
    }

    Ok(None)
}

fn parse_mp4_metadata(file: &mut File) -> io::Result<VideoMetadata> {
    let mut meta = VideoMetadata {
        format: "MP4".to_string(),
        quality_badge: "SD".to_string(),
        ..Default::default()
    };

    let mut buffer = vec![0u8; 5 * 1024 * 1024];
    let n = file.read(&mut buffer)?;
    let slice = &buffer[..n];

    // Find timescale and duration in mvhd
    if let Some(pos) = find_subsequence(slice, b"mvhd") {
        let p = pos + 4;
        if p + 20 <= slice.len() {
            let version = slice[p];
            if version == 0 && p + 20 <= slice.len() {
                let timescale = u32::from_be_bytes([slice[p + 12], slice[p + 13], slice[p + 14], slice[p + 15]]) as u64;
                let duration = u32::from_be_bytes([slice[p + 16], slice[p + 17], slice[p + 18], slice[p + 19]]) as u64;
                if timescale > 0 {
                    meta.duration_ms = (duration * 1000) / timescale;
                }
            } else if version == 1 && p + 28 <= slice.len() {
                let timescale = u32::from_be_bytes([slice[p + 20], slice[p + 21], slice[p + 22], slice[p + 23]]) as u64;
                let duration = u64::from_be_bytes([
                    slice[p + 24], slice[p + 25], slice[p + 26], slice[p + 27],
                    slice[p + 28], slice[p + 29], slice[p + 30], slice[p + 31],
                ]);
                if timescale > 0 {
                    meta.duration_ms = (duration * 1000) / timescale;
                }
            }
        }
    }

    // Find track dimensions in tkhd
    if let Some(pos) = find_subsequence(slice, b"tkhd") {
        let p = pos + 4;
        // In tkhd v0: dimensions are at offset 76 (width: 4B, height: 4B in 16.16 fixed-point)
        // In tkhd v1: dimensions are at offset 88
        if p + 84 <= slice.len() {
            let version = slice[p];
            let dim_offset = if version == 0 { p + 76 } else { p + 88 };
            if dim_offset + 8 <= slice.len() {
                // Read 16.16 fixed point: integer is first 2 bytes
                let w = u16::from_be_bytes([slice[dim_offset], slice[dim_offset + 1]]) as u32;
                let h = u16::from_be_bytes([slice[dim_offset + 4], slice[dim_offset + 5]]) as u32;
                meta.width = w;
                meta.height = h;
            }
        }
    }

    meta.quality_badge = get_quality_badge(meta.width, meta.height);
    Ok(meta)
}

// -----------------------------------------------------------------------------
// MKV / WebM EBML Parser
// -----------------------------------------------------------------------------

fn extract_mkv_poster(file: &mut File) -> io::Result<Option<Vec<u8>>> {
    let mut buffer = vec![0u8; 4 * 1024 * 1024];
    let n = file.read(&mut buffer)?;
    let slice = &buffer[..n];

    // Find FileData element ID: 0x46, 0x5C
    if let Some(pos) = find_subsequence(slice, &[0x46, 0x5C]) {
        let p = pos + 2;
        if p + 4 <= slice.len() {
            // Read EBML vint size
            let (vint_len, vint_val) = read_ebml_vint(&slice[p..]);
            let data_start = p + vint_len;
            let data_end = data_start + vint_val as usize;
            if data_end <= slice.len() {
                let bytes = slice[data_start..data_end].to_vec();
                if bytes.starts_with(&[0xFF, 0xD8]) || bytes.starts_with(&[0x89, b'P', b'N', b'G']) {
                    return Ok(Some(bytes));
                }
            }
        }
    }

    Ok(None)
}

fn parse_mkv_metadata(file: &mut File) -> io::Result<VideoMetadata> {
    let mut meta = VideoMetadata {
        format: "MKV".to_string(),
        quality_badge: "SD".to_string(),
        ..Default::default()
    };

    let mut buffer = vec![0u8; 2 * 1024 * 1024];
    let n = file.read(&mut buffer)?;
    let slice = &buffer[..n];

    // Find PixelWidth element ID: 0xB0
    if let Some(pos) = find_subsequence(slice, &[0xB0]) {
        let p = pos + 1;
        if p + 2 <= slice.len() {
            let (vint_len, vint_val) = read_ebml_vint(&slice[p..]);
            let start = p + vint_len;
            if start + vint_val as usize <= slice.len() {
                meta.width = read_be_uint(&slice[start..start + vint_val as usize]) as u32;
            }
        }
    }

    // Find PixelHeight element ID: 0xBA
    if let Some(pos) = find_subsequence(slice, &[0xBA]) {
        let p = pos + 1;
        if p + 2 <= slice.len() {
            let (vint_len, vint_val) = read_ebml_vint(&slice[p..]);
            let start = p + vint_len;
            if start + vint_val as usize <= slice.len() {
                meta.height = read_be_uint(&slice[start..start + vint_val as usize]) as u32;
            }
        }
    }

    // Find Duration element ID: 0x44, 0x89
    if let Some(pos) = find_subsequence(slice, &[0x44, 0x89]) {
        let p = pos + 2;
        if p + 4 <= slice.len() {
            let (vint_len, vint_val) = read_ebml_vint(&slice[p..]);
            let start = p + vint_len;
            if vint_val == 4 && start + 4 <= slice.len() {
                let f_bytes = [slice[start], slice[start + 1], slice[start + 2], slice[start + 3]];
                let duration_f32 = f32::from_be_bytes(f_bytes);
                meta.duration_ms = duration_f32 as u64;
            } else if vint_val == 8 && start + 8 <= slice.len() {
                let f_bytes = [
                    slice[start], slice[start + 1], slice[start + 2], slice[start + 3],
                    slice[start + 4], slice[start + 5], slice[start + 6], slice[start + 7],
                ];
                let duration_f64 = f64::from_be_bytes(f_bytes);
                meta.duration_ms = duration_f64 as u64;
            }
        }
    }

    meta.quality_badge = get_quality_badge(meta.width, meta.height);
    Ok(meta)
}

fn get_quality_badge(w: u32, h: u32) -> String {
    let max_dim = std::cmp::max(w, h);
    if max_dim == 0 {
        return String::new();
    }
    if max_dim >= 3840 {
        "4K".to_string()
    } else if max_dim >= 1920 {
        "1080p".to_string()
    } else if max_dim >= 1280 {
        "720p".to_string()
    } else {
        "SD".to_string()
    }
}

fn read_ebml_vint(slice: &[u8]) -> (usize, u64) {
    if slice.is_empty() {
        return (0, 0);
    }
    let first = slice[0];
    let len = first.leading_zeros() as usize + 1;
    if len > 8 || len > slice.len() {
        return (0, 0);
    }
    let mask = (1 << (8 - len)) - 1;
    let mut val = (first & mask) as u64;
    for i in 1..len {
        val = (val << 8) | (slice[i] as u64);
    }
    (len, val)
}

fn read_be_uint(slice: &[u8]) -> u64 {
    let mut val = 0u64;
    for &b in slice {
        val = (val << 8) | (b as u64);
    }
    val
}

fn find_subsequence(haystack: &[u8], needle: &[u8]) -> Option<usize> {
    haystack.windows(needle.len()).position(|window| window == needle)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_quality_badges() {
        assert_eq!(get_quality_badge(3840, 2160), "4K");
        assert_eq!(get_quality_badge(1920, 1080), "1080p");
        assert_eq!(get_quality_badge(1080, 1920), "1080p"); // vertical video
        assert_eq!(get_quality_badge(1280, 720), "720p");
        assert_eq!(get_quality_badge(640, 480), "SD");
        assert_eq!(get_quality_badge(0, 0), "");
    }

    #[test]
    fn test_find_subsequence() {
        let hay = b"hello world from rust";
        assert_eq!(find_subsequence(hay, b"world"), Some(6));
        assert_eq!(find_subsequence(hay, b"galaxy"), None);
    }

    #[test]
    fn test_nonexistent_video() {
        assert!(extract_video_poster("nonexistent.mp4").unwrap().is_none());
        assert!(parse_video_metadata("nonexistent.mp4").is_err());
    }
}

