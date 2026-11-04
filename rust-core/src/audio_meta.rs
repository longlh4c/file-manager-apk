use std::fs::File;
use std::io::{self, Cursor, Read, Seek, SeekFrom};
use std::path::Path;
use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Debug, Default)]
pub struct AudioMetadata {
    pub title: Option<String>,
    pub artist: Option<String>,
    pub album: Option<String>,
    pub year: Option<String>,
    pub duration_ms: Option<u64>,
    pub bitrate_kbps: Option<u32>,
    pub sample_rate_hz: Option<u32>,
}

pub fn extract_audio_cover(path_str: &str) -> io::Result<Option<Vec<u8>>> {
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
        "mp3" => extract_id3v2_cover(&mut file),
        "flac" => extract_flac_cover(&mut file),
        "m4a" | "aac" | "mp4" => extract_mp4_cover(&mut file),
        _ => Ok(None),
    }
}

pub fn parse_audio_tags(path_str: &str) -> io::Result<AudioMetadata> {
    let path = Path::new(path_str);
    let mut file = File::open(path)?;
    let ext = path
        .extension()
        .and_then(|s| s.to_str())
        .unwrap_or("")
        .to_ascii_lowercase();

    match ext.as_str() {
        "mp3" => parse_id3v2_tags(&mut file),
        "flac" => parse_flac_tags(&mut file),
        "m4a" | "aac" | "mp4" => parse_mp4_audio_tags(&mut file),
        _ => Ok(AudioMetadata::default()),
    }
}

// -----------------------------------------------------------------------------
// ID3v2 (MP3) Parser
// -----------------------------------------------------------------------------

fn extract_id3v2_cover(file: &mut File) -> io::Result<Option<Vec<u8>>> {
    let mut header = [0u8; 10];
    if file.read_exact(&mut header).is_err() {
        return Ok(None);
    }

    if &header[0..3] != b"ID3" {
        return Ok(None);
    }

    let version = header[3];
    let tag_size = syncsafe_to_u32([header[6], header[7], header[8], header[9]]) as usize;
    if tag_size == 0 || tag_size > 30 * 1024 * 1024 {
        return Ok(None);
    }

    let mut tag_data = vec![0u8; tag_size];
    if file.read_exact(&mut tag_data).is_err() {
        return Ok(None);
    }

    let mut pos = 0;
    while pos + 10 <= tag_data.len() {
        let frame_id = &tag_data[pos..pos + 4];
        if frame_id[0] == 0 {
            break;
        }

        let frame_size = if version == 4 {
            syncsafe_to_u32([
                tag_data[pos + 4],
                tag_data[pos + 5],
                tag_data[pos + 6],
                tag_data[pos + 7],
            ]) as usize
        } else {
            u32::from_be_bytes([
                tag_data[pos + 4],
                tag_data[pos + 5],
                tag_data[pos + 6],
                tag_data[pos + 7],
            ]) as usize
        };

        pos += 10;
        if pos + frame_size > tag_data.len() {
            break;
        }

        let frame_data = &tag_data[pos..pos + frame_size];
        pos += frame_size;

        if frame_id == b"APIC" && frame_data.len() > 4 {
            // APIC structure: [encoding: 1B] [mime: null-terminated ASCII] [picture_type: 1B] [desc: null-terminated] [picture_data...]
            let enc = frame_data[0];
            let mut p = 1;
            while p < frame_data.len() && frame_data[p] != 0 {
                p += 1;
            }
            if p >= frame_data.len() {
                continue;
            }
            p += 1; // skip null byte of MIME
            if p >= frame_data.len() {
                continue;
            }
            let _pic_type = frame_data[p];
            p += 1;

            // skip description
            if enc == 0 || enc == 3 {
                // ISO-8859-1 or UTF-8 (1-byte null)
                while p < frame_data.len() && frame_data[p] != 0 {
                    p += 1;
                }
                p += 1;
            } else {
                // UTF-16 (2-byte null)
                while p + 1 < frame_data.len() && !(frame_data[p] == 0 && frame_data[p + 1] == 0) {
                    p += 2;
                }
                p += 2;
            }

            if p < frame_data.len() {
                return Ok(Some(frame_data[p..].to_vec()));
            }
        }
    }

    Ok(None)
}

fn parse_id3v2_tags(file: &mut File) -> io::Result<AudioMetadata> {
    let mut meta = AudioMetadata::default();
    let mut header = [0u8; 10];
    if file.read_exact(&mut header).is_err() {
        return Ok(meta);
    }

    if &header[0..3] != b"ID3" {
        return Ok(meta);
    }

    let version = header[3];
    let tag_size = syncsafe_to_u32([header[6], header[7], header[8], header[9]]) as usize;
    if tag_size == 0 || tag_size > 10 * 1024 * 1024 {
        return Ok(meta);
    }

    let mut tag_data = vec![0u8; tag_size];
    if file.read_exact(&mut tag_data).is_err() {
        return Ok(meta);
    }

    let mut pos = 0;
    while pos + 10 <= tag_data.len() {
        let frame_id = &tag_data[pos..pos + 4];
        if frame_id[0] == 0 {
            break;
        }

        let frame_size = if version == 4 {
            syncsafe_to_u32([
                tag_data[pos + 4],
                tag_data[pos + 5],
                tag_data[pos + 6],
                tag_data[pos + 7],
            ]) as usize
        } else {
            u32::from_be_bytes([
                tag_data[pos + 4],
                tag_data[pos + 5],
                tag_data[pos + 6],
                tag_data[pos + 7],
            ]) as usize
        };

        pos += 10;
        if pos + frame_size > tag_data.len() {
            break;
        }

        let frame_data = &tag_data[pos..pos + frame_size];
        pos += frame_size;

        if frame_data.is_empty() {
            continue;
        }

        match frame_id {
            b"TIT2" => meta.title = decode_id3_text(frame_data),
            b"TPE1" => meta.artist = decode_id3_text(frame_data),
            b"TALB" => meta.album = decode_id3_text(frame_data),
            b"TYER" | b"TDRC" => meta.year = decode_id3_text(frame_data),
            b"TLEN" => {
                if let Some(s) = decode_id3_text(frame_data) {
                    meta.duration_ms = s.trim().parse::<u64>().ok();
                }
            }
            _ => {}
        }
    }

    Ok(meta)
}

fn syncsafe_to_u32(bytes: [u8; 4]) -> u32 {
    ((bytes[0] as u32) << 21)
        | ((bytes[1] as u32) << 14)
        | ((bytes[2] as u32) << 7)
        | (bytes[3] as u32)
}

fn decode_id3_text(data: &[u8]) -> Option<String> {
    if data.is_empty() {
        return None;
    }
    let encoding = data[0];
    let payload = &data[1..];
    match encoding {
        0 => Some(payload.iter().map(|&b| b as char).collect()), // ISO-8859-1
        1 => {
            // UTF-16 with BOM
            if payload.len() < 2 {
                return None;
            }
            let u16s: Vec<u16> = payload
                .chunks_exact(2)
                .map(|c| u16::from_le_bytes([c[0], c[1]]))
                .collect();
            Some(String::from_utf16_lossy(&u16s).trim_matches('\0').to_string())
        }
        2 => {
            // UTF-16BE
            let u16s: Vec<u16> = payload
                .chunks_exact(2)
                .map(|c| u16::from_be_bytes([c[0], c[1]]))
                .collect();
            Some(String::from_utf16_lossy(&u16s).trim_matches('\0').to_string())
        }
        3 => Some(String::from_utf8_lossy(payload).trim_matches('\0').to_string()),
        _ => None,
    }
}

// -----------------------------------------------------------------------------
// FLAC Parser
// -----------------------------------------------------------------------------

fn extract_flac_cover(file: &mut File) -> io::Result<Option<Vec<u8>>> {
    let mut magic = [0u8; 4];
    if file.read_exact(&mut magic).is_err() || &magic != b"fLaC" {
        return Ok(None);
    }

    loop {
        let mut header = [0u8; 4];
        if file.read_exact(&mut header).is_err() {
            break;
        }

        let is_last = (header[0] & 0x80) != 0;
        let block_type = header[0] & 0x7F;
        let length = (((header[1] as u32) << 16)
            | ((header[2] as u32) << 8)
            | (header[3] as u32)) as usize;

        if block_type == 6 {
            // METADATA_BLOCK_PICTURE
            let mut block_data = vec![0u8; length];
            file.read_exact(&mut block_data)?;
            let mut cursor = Cursor::new(&block_data);

            let mut u32_buf = [0u8; 4];
            // Skip picture type
            if cursor.read_exact(&mut u32_buf).is_err() {
                return Ok(None);
            }
            // Read MIME length
            if cursor.read_exact(&mut u32_buf).is_err() {
                return Ok(None);
            }
            let mime_len = u32::from_be_bytes(u32_buf) as usize;
            cursor.set_position(cursor.position() + mime_len as u64);

            // Read Description length
            if cursor.read_exact(&mut u32_buf).is_err() {
                return Ok(None);
            }
            let desc_len = u32::from_be_bytes(u32_buf) as usize;
            cursor.set_position(cursor.position() + desc_len as u64);

            // Skip width(4), height(4), depth(4), colors(4) = 16 bytes
            cursor.set_position(cursor.position() + 16);

            // Read picture data length
            if cursor.read_exact(&mut u32_buf).is_err() {
                return Ok(None);
            }
            let data_len = u32::from_be_bytes(u32_buf) as usize;
            let start = cursor.position() as usize;
            if start + data_len <= block_data.len() {
                return Ok(Some(block_data[start..start + data_len].to_vec()));
            }
        } else {
            file.seek(SeekFrom::Current(length as i64))?;
        }

        if is_last {
            break;
        }
    }

    Ok(None)
}

fn parse_flac_tags(file: &mut File) -> io::Result<AudioMetadata> {
    let mut meta = AudioMetadata::default();
    let mut magic = [0u8; 4];
    if file.read_exact(&mut magic).is_err() || &magic != b"fLaC" {
        return Ok(meta);
    }

    loop {
        let mut header = [0u8; 4];
        if file.read_exact(&mut header).is_err() {
            break;
        }

        let is_last = (header[0] & 0x80) != 0;
        let block_type = header[0] & 0x7F;
        let length = (((header[1] as u32) << 16)
            | ((header[2] as u32) << 8)
            | (header[3] as u32)) as usize;

        if block_type == 0 {
            // STREAMINFO block
            let mut info = [0u8; 34];
            if file.read_exact(&mut info).is_ok() {
                let sample_rate = ((info[10] as u32) << 12)
                    | ((info[11] as u32) << 4)
                    | ((info[12] as u32) >> 4);
                let total_samples = (((info[13] as u64) & 0x0F) << 32)
                    | ((info[14] as u64) << 24)
                    | ((info[15] as u64) << 16)
                    | ((info[16] as u64) << 8)
                    | (info[17] as u64);
                meta.sample_rate_hz = Some(sample_rate);
                if sample_rate > 0 {
                    meta.duration_ms = Some((total_samples * 1000) / (sample_rate as u64));
                }
            }
            let remaining = length.saturating_sub(34);
            if remaining > 0 {
                file.seek(SeekFrom::Current(remaining as i64))?;
            }
        } else if block_type == 4 {
            // VORBIS_COMMENT block
            let mut block_data = vec![0u8; length];
            if file.read_exact(&mut block_data).is_ok() {
                parse_vorbis_comments(&block_data, &mut meta);
            }
        } else {
            file.seek(SeekFrom::Current(length as i64))?;
        }

        if is_last {
            break;
        }
    }

    Ok(meta)
}

fn parse_vorbis_comments(data: &[u8], meta: &mut AudioMetadata) {
    if data.len() < 8 {
        return;
    }
    let mut cursor = Cursor::new(data);
    let mut u32_buf = [0u8; 4];

    // Vendor string length
    if cursor.read_exact(&mut u32_buf).is_err() {
        return;
    }
    let vendor_len = u32::from_le_bytes(u32_buf) as u64;
    cursor.set_position(cursor.position() + vendor_len);

    // Comments count
    if cursor.read_exact(&mut u32_buf).is_err() {
        return;
    }
    let count = u32::from_le_bytes(u32_buf);

    for _ in 0..count {
        if cursor.read_exact(&mut u32_buf).is_err() {
            break;
        }
        let comment_len = u32::from_le_bytes(u32_buf) as usize;
        let mut comment_bytes = vec![0u8; comment_len];
        if cursor.read_exact(&mut comment_bytes).is_err() {
            break;
        }

        if let Ok(comment_str) = String::from_utf8(comment_bytes) {
            let mut split = comment_str.splitn(2, '=');
            if let (Some(key), Some(val)) = (split.next(), split.next()) {
                match key.to_ascii_uppercase().as_str() {
                    "TITLE" => meta.title = Some(val.to_string()),
                    "ARTIST" => meta.artist = Some(val.to_string()),
                    "ALBUM" => meta.album = Some(val.to_string()),
                    "DATE" | "YEAR" => meta.year = Some(val.to_string()),
                    _ => {}
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// MP4 / M4A Parser
// -----------------------------------------------------------------------------

fn extract_mp4_cover(file: &mut File) -> io::Result<Option<Vec<u8>>> {
    // Scan up to 10MB of file for moov/udta/meta/ilst/covr
    let mut buffer = vec![0u8; 5 * 1024 * 1024];
    let n = file.read(&mut buffer)?;
    let slice = &buffer[..n];

    if let Some(pos) = find_subsequence(slice, b"covr") {
        let p = pos + 4;
        if p + 16 <= slice.len() {
            // Usually follows: [size: 4B] "data" [type: 4B] [locale: 4B] [bytes...]
            if &slice[p + 4..p + 8] == b"data" {
                let data_size = u32::from_be_bytes([slice[p], slice[p + 1], slice[p + 2], slice[p + 3]]) as usize;
                let data_offset = p + 16;
                let payload_len = data_size.saturating_sub(16);
                if data_offset + payload_len <= slice.len() {
                    return Ok(Some(slice[data_offset..data_offset + payload_len].to_vec()));
                }
            }
        }
    }

    Ok(None)
}

fn parse_mp4_audio_tags(file: &mut File) -> io::Result<AudioMetadata> {
    let mut meta = AudioMetadata::default();
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
                    meta.duration_ms = Some((duration * 1000) / timescale);
                }
            }
        }
    }

    // Title ©nam
    if let Some(pos) = find_subsequence(slice, &[0xA9, b'n', b'a', b'm']) {
        meta.title = extract_mp4_data_text(slice, pos + 4);
    }
    // Artist ©ART
    if let Some(pos) = find_subsequence(slice, &[0xA9, b'A', b'R', b'T']) {
        meta.artist = extract_mp4_data_text(slice, pos + 4);
    }
    // Album ©alb
    if let Some(pos) = find_subsequence(slice, &[0xA9, b'a', b'l', b'b']) {
        meta.album = extract_mp4_data_text(slice, pos + 4);
    }

    Ok(meta)
}

fn extract_mp4_data_text(slice: &[u8], p: usize) -> Option<String> {
    if p + 16 > slice.len() {
        return None;
    }
    if &slice[p + 4..p + 8] == b"data" {
        let data_size = u32::from_be_bytes([slice[p], slice[p + 1], slice[p + 2], slice[p + 3]]) as usize;
        let data_offset = p + 16;
        let payload_len = data_size.saturating_sub(16);
        if data_offset + payload_len <= slice.len() {
            return Some(String::from_utf8_lossy(&slice[data_offset..data_offset + payload_len]).to_string());
        }
    }
    None
}

fn find_subsequence(haystack: &[u8], needle: &[u8]) -> Option<usize> {
    haystack.windows(needle.len()).position(|window| window == needle)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_synchsafe_u32() {
        assert_eq!(syncsafe_to_u32([0, 0, 0, 0]), 0);
        assert_eq!(syncsafe_to_u32([0, 0, 1, 0]), 128);
        assert_eq!(syncsafe_to_u32([0, 0, 0x01, 0x7F]), 255);
    }

    #[test]
    fn test_nonexistent_audio() {
        assert!(extract_audio_cover("nonexistent.mp3").unwrap().is_none());
        assert!(parse_audio_tags("nonexistent.mp3").is_err());
    }
}

