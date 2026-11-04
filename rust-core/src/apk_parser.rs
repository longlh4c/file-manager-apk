use std::fs::File;
use std::io::{self, Cursor, Read};
use std::path::Path;
use serde::{Deserialize, Serialize};
use zip::ZipArchive;

#[derive(Serialize, Deserialize, Debug, Default)]
pub struct ApkInfo {
    pub package_name: Option<String>,
    pub version_name: Option<String>,
    pub version_code: Option<u32>,
}

pub fn extract_apk_icon(apk_path: &str) -> io::Result<Option<Vec<u8>>> {
    let file = match File::open(Path::new(apk_path)) {
        Ok(f) => f,
        Err(_) => return Ok(None),
    };
    let mut zip = match ZipArchive::new(file) {
        Ok(z) => z,
        Err(_) => return Ok(None),
    };

    let mut best_index: Option<usize> = None;
    let mut best_score: i32 = -1;

    for i in 0..zip.len() {
        let entry = match zip.by_index(i) {
            Ok(e) => e,
            Err(_) => continue,
        };

        let name = entry.name().to_ascii_lowercase();

        // Must be a raster image
        if !(name.ends_with(".png") || name.ends_with(".webp")) {
            continue;
        }

        // Must be in res/mipmap or res/drawable
        if !name.starts_with("res/") {
            continue;
        }

        let mut score = 0;
        if name.contains("ic_launcher") {
            score += 100;
        } else if name.contains("app_icon") {
            score += 80;
        } else if name.contains("icon") {
            score += 40;
        } else {
            continue;
        }

        if name.contains("xxxhdpi") {
            score += 50;
        } else if name.contains("xxhdpi") {
            score += 40;
        } else if name.contains("xhdpi") {
            score += 30;
        } else if name.contains("hdpi") {
            score += 20;
        } else if name.contains("mdpi") {
            score += 10;
        }

        // Avoid round icons if regular launcher icon is available
        if name.contains("round") {
            score -= 5;
        }

        if score > best_score {
            best_score = score;
            best_index = Some(i);
        }
    }

    if let Some(idx) = best_index {
        let mut entry = zip.by_index(idx)
            .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e.to_string()))?;
        let mut bytes = Vec::with_capacity(entry.size() as usize);
        entry.read_to_end(&mut bytes)?;
        Ok(Some(bytes))
    } else {
        Ok(None)
    }
}

pub fn parse_apk_manifest(apk_path: &str) -> io::Result<Option<ApkInfo>> {
    let file = match File::open(Path::new(apk_path)) {
        Ok(f) => f,
        Err(_) => return Ok(None),
    };
    let mut zip = match ZipArchive::new(file) {
        Ok(z) => z,
        Err(_) => return Ok(None),
    };

    let mut manifest_entry = match zip.by_name("AndroidManifest.xml") {
        Ok(e) => e,
        Err(_) => return Ok(None),
    };

    let mut data = Vec::with_capacity(manifest_entry.size() as usize);
    manifest_entry.read_to_end(&mut data)?;

    Ok(parse_axml(&data))
}

fn parse_axml(data: &[u8]) -> Option<ApkInfo> {
    if data.len() < 8 {
        return None;
    }
    let mut cursor = Cursor::new(data);
    let magic = read_u32_le(&mut cursor)?;
    if magic != 0x00080003 {
        return None;
    }
    let _file_size = read_u32_le(&mut cursor)?;

    // Read chunks until string pool is found
    let mut string_pool = Vec::new();

    while (cursor.position() as usize) < data.len() {
        let chunk_type = read_u32_le(&mut cursor)?;
        let chunk_size = read_u32_le(&mut cursor)?;

        if chunk_size < 8 {
            break;
        }

        let chunk_start = (cursor.position() as usize) - 8;
        let chunk_end = chunk_start + chunk_size as usize;

        if chunk_type == 0x00010001 || chunk_type == 0x001C0001 {
            // String Pool Chunk
            let str_count = read_u32_le(&mut cursor)? as usize;
            let _style_count = read_u32_le(&mut cursor)?;
            let flags = read_u32_le(&mut cursor)?;
            let strings_start = read_u32_le(&mut cursor)? as usize;
            let _styles_start = read_u32_le(&mut cursor)?;

            let is_utf8 = (flags & (1 << 8)) != 0;

            let mut offsets = Vec::with_capacity(str_count);
            for _ in 0..str_count {
                offsets.push(read_u32_le(&mut cursor)? as usize);
            }

            let pool_base = chunk_start + strings_start;
            for offset in offsets {
                let str_pos = pool_base + offset;
                if str_pos >= data.len() {
                    string_pool.push(String::new());
                    continue;
                }
                if is_utf8 {
                    // Length is u8 or u16
                    let mut p = str_pos;
                    if p >= data.len() {
                        string_pool.push(String::new());
                        continue;
                    }
                    let _char_len = data[p];
                    p += if data[p] > 0x7F { 2 } else { 1 };
                    if p >= data.len() {
                        string_pool.push(String::new());
                        continue;
                    }
                    let byte_len = if data[p] > 0x7F {
                        let l = (((data[p] & 0x7F) as usize) << 8) | (data[p + 1] as usize);
                        p += 2;
                        l
                    } else {
                        let l = data[p] as usize;
                        p += 1;
                        l
                    };
                    if p + byte_len <= data.len() {
                        let s = String::from_utf8_lossy(&data[p..p + byte_len]).to_string();
                        string_pool.push(s);
                    } else {
                        string_pool.push(String::new());
                    }
                } else {
                    // UTF-16LE
                    let mut p = str_pos;
                    if p + 2 > data.len() {
                        string_pool.push(String::new());
                        continue;
                    }
                    let len = (data[p] as usize) | ((data[p + 1] as usize) << 8);
                    p += 2;
                    let byte_len = len * 2;
                    if p + byte_len <= data.len() {
                        let u16_slice: Vec<u16> = data[p..p + byte_len]
                            .chunks_exact(2)
                            .map(|c| (c[0] as u16) | ((c[1] as u16) << 8))
                            .collect();
                        let s = String::from_utf16_lossy(&u16_slice);
                        string_pool.push(s);
                    } else {
                        string_pool.push(String::new());
                    }
                }
            }
        } else if chunk_type == 0x00100102 {
            // Start Element Chunk (e.g. <manifest>)
            let _line_num = read_u32_le(&mut cursor)?;
            let _comment = read_u32_le(&mut cursor)?;
            let _ns_idx = read_u32_le(&mut cursor)?;
            let name_idx = read_u32_le(&mut cursor)? as usize;
            let _attr_start = read_u16_le(&mut cursor)?;
            let attr_size = read_u16_le(&mut cursor)? as usize;
            let attr_count = read_u16_le(&mut cursor)? as usize;
            let _id_idx = read_u16_le(&mut cursor)?;
            let _class_idx = read_u16_le(&mut cursor)?;
            let _style_idx = read_u16_le(&mut cursor)?;

            let tag_name = string_pool.get(name_idx).map(|s| s.as_str()).unwrap_or("");
            if tag_name == "manifest" {
                let mut info = ApkInfo::default();
                for _ in 0..attr_count {
                    let _attr_ns = read_u32_le(&mut cursor)?;
                    let attr_name_idx = read_u32_le(&mut cursor)? as usize;
                    let attr_val_idx = read_u32_le(&mut cursor)?;
                    let _attr_type = read_u32_le(&mut cursor)?;
                    let attr_data = read_u32_le(&mut cursor)?;

                    let attr_name = string_pool.get(attr_name_idx).map(|s| s.as_str()).unwrap_or("");
                    if attr_name == "package" {
                        if attr_val_idx != 0xFFFFFFFF {
                            info.package_name = string_pool.get(attr_val_idx as usize).cloned();
                        }
                    } else if attr_name == "versionName" {
                        if attr_val_idx != 0xFFFFFFFF {
                            info.version_name = string_pool.get(attr_val_idx as usize).cloned();
                        }
                    } else if attr_name == "versionCode" {
                        info.version_code = Some(attr_data);
                    }

                    // Skip any leftover attribute bytes if attr_size > 20
                    if attr_size > 20 {
                        let skip = attr_size - 20;
                        cursor.set_position(cursor.position() + skip as u64);
                    }
                }
                return Some(info);
            }
        }

        if chunk_end <= data.len() && (cursor.position() as usize) < chunk_end {
            cursor.set_position(chunk_end as u64);
        }
    }

    None
}

fn read_u32_le(cursor: &mut Cursor<&[u8]>) -> Option<u32> {
    let mut buf = [0u8; 4];
    cursor.read_exact(&mut buf).ok()?;
    Some(u32::from_le_bytes(buf))
}

fn read_u16_le(cursor: &mut Cursor<&[u8]>) -> Option<u16> {
    let mut buf = [0u8; 2];
    cursor.read_exact(&mut buf).ok()?;
    Some(u16::from_le_bytes(buf))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_nonexistent_apk() {
        assert!(extract_apk_icon("nonexistent.apk").unwrap().is_none());
        assert!(parse_apk_manifest("nonexistent.apk").unwrap().is_none());
    }

    #[test]
    fn test_read_u32_le() {
        let data = [0x01, 0x02, 0x03, 0x04];
        let mut cursor = Cursor::new(&data[..]);
        assert_eq!(read_u32_le(&mut cursor), Some(0x04030201));
    }
}

