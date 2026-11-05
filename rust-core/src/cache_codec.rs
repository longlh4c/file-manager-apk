use std::fs::File;
use std::io::{BufReader, BufWriter};
use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
pub struct CacheFolderEntry {
    pub id: String,
    pub name: String,
    pub path: String,
    pub size: i64,
    pub last_modified: i64,
    pub is_directory: bool,
    pub mime_type: String,
    pub item_count: i32,
    pub subfolder_count: i32,
    pub file_child_count: i32,
    pub extension: String,
    pub thumbnail_uri: String,
    pub badge: String,
    pub web_view_link: String,
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
pub struct CacheFolderPayload {
    pub timestamp: i64,
    pub items: Vec<CacheFolderEntry>,
}

pub fn save_cache_to_file(path: &str, timestamp: i64, items: Vec<CacheFolderEntry>) -> Result<(), String> {
    let file = File::create(path).map_err(|e| format!("Failed to create file {}: {}", path, e))?;
    let writer = BufWriter::new(file);
    let payload = CacheFolderPayload { timestamp, items };
    bincode::serialize_into(writer, &payload)
        .map_err(|e| format!("Failed to serialize binary cache: {}", e))
}

pub fn load_cache_from_file(path: &str) -> Result<CacheFolderPayload, String> {
    let file = File::open(path).map_err(|e| format!("Failed to open file {}: {}", path, e))?;
    let reader = BufReader::new(file);
    bincode::deserialize_from(reader)
        .map_err(|e| format!("Failed to deserialize binary cache: {}", e))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::env;

    #[test]
    fn test_binary_cache_roundtrip() {
        let temp_dir = env::temp_dir();
        let test_file = temp_dir.join("owlfm_test_cache.bin");
        let path_str = test_file.to_str().unwrap();

        let entries = vec![
            CacheFolderEntry {
                id: "1".into(),
                name: "Photos".into(),
                path: "/Photos".into(),
                size: 0,
                last_modified: 1700000000000,
                is_directory: true,
                mime_type: "vnd.android.document/directory".into(),
                item_count: 42,
                subfolder_count: 2,
                file_child_count: 40,
                extension: "".into(),
                thumbnail_uri: "".into(),
                badge: "CAMERA".into(),
                web_view_link: "".into(),
            },
            CacheFolderEntry {
                id: "2".into(),
                name: "pic.jpg".into(),
                path: "/Photos/pic.jpg".into(),
                size: 1048576,
                last_modified: 1700000001000,
                is_directory: false,
                mime_type: "image/jpeg".into(),
                item_count: 0,
                subfolder_count: 0,
                file_child_count: 0,
                extension: "jpg".into(),
                thumbnail_uri: "content://thumb/2".into(),
                badge: "STANDARD".into(),
                web_view_link: "https://drive.google.com/file/2".into(),
            },
        ];

        let ts = 1700000050000i64;
        save_cache_to_file(path_str, ts, entries.clone()).expect("save_cache_to_file failed");

        let loaded = load_cache_from_file(path_str).expect("load_cache_from_file failed");
        assert_eq!(loaded.timestamp, ts);
        assert_eq!(loaded.items.len(), 2);
        assert_eq!(loaded.items[0], entries[0]);
        assert_eq!(loaded.items[1], entries[1]);

        let _ = std::fs::remove_file(test_file);
    }
}
