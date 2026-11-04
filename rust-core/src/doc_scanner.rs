use std::collections::HashSet;
use std::fs;
use std::path::Path;
use std::time::UNIX_EPOCH;
use rayon::prelude::*;
use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct DocumentEntry {
    pub path: String,
    pub name: String,
    pub size: u64,
    pub last_modified: u64,
}

pub fn scan_documents(
    root_paths: &[String],
    allowed_extensions: &HashSet<String>,
    max_depth: usize,
) -> Vec<DocumentEntry> {
    root_paths
        .par_iter()
        .flat_map(|root| {
            let path = Path::new(root);
            if !path.exists() || !path.is_dir() {
                return Vec::new();
            }
            let mut results = Vec::new();
            scan_dir_recursive(path, allowed_extensions, 0, max_depth, &mut results);
            results
        })
        .collect()
}

fn scan_dir_recursive(
    dir: &Path,
    allowed_exts: &HashSet<String>,
    current_depth: usize,
    max_depth: usize,
    out: &mut Vec<DocumentEntry>,
) {
    if current_depth > max_depth {
        return;
    }

    let entries = match fs::read_dir(dir) {
        Ok(e) => e,
        Err(_) => return,
    };

    for entry_res in entries {
        let entry = match entry_res {
            Ok(e) => e,
            Err(_) => continue,
        };

        let file_name = entry.file_name();
        let name_str = file_name.to_string_lossy();

        if name_str.starts_with('.') {
            continue;
        }

        let file_type = match entry.file_type() {
            Ok(ft) => ft,
            Err(_) => continue,
        };

        let path = entry.path();

        if file_type.is_dir() {
            // Skip system Android folders
            if name_str == "Android" {
                continue;
            }
            scan_dir_recursive(&path, allowed_exts, current_depth + 1, max_depth, out);
        } else if file_type.is_file() {
            if let Some(ext) = path.extension() {
                let ext_str = ext.to_string_lossy().to_ascii_lowercase();
                if allowed_exts.contains(&ext_str) {
                    let (size, mtime_ms) = match entry.metadata() {
                        Ok(meta) => {
                            let size = meta.len();
                            let mtime = meta
                                .modified()
                                .ok()
                                .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
                                .map(|d| d.as_millis() as u64)
                                .unwrap_or(0);
                            (size, mtime)
                        }
                        Err(_) => (0, 0),
                    };

                    out.push(DocumentEntry {
                        path: path.to_string_lossy().into_owned(),
                        name: name_str.into_owned(),
                        size,
                        last_modified: mtime_ms,
                    });
                }
            }
        }
    }
}
