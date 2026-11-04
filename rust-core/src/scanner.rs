use serde::{Deserialize, Serialize};
use std::fs::{self, DirEntry};
use std::path::Path;
use std::time::UNIX_EPOCH;
use walkdir::WalkDir;

#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct NativeDirEntry {
    pub name: String,
    pub path: String,
    pub is_directory: bool,
    pub size: u64,
    pub last_modified: i64,
    pub item_count: u32,
    pub subfolder_count: u32,
    pub is_hidden: bool,
}

#[derive(Serialize, Deserialize, Debug)]
pub struct ListDirResult {
    pub success: bool,
    pub entries: Option<Vec<NativeDirEntry>>,
    pub error: Option<String>,
}

#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct NativeSearchEntry {
    pub name: String,
    pub path: String,
    pub is_directory: bool,
    pub size: u64,
    pub last_modified: i64,
    pub extension: String,
}

#[derive(Serialize, Deserialize, Debug)]
pub struct SearchResult {
    pub success: bool,
    pub entries: Option<Vec<NativeSearchEntry>>,
    pub error: Option<String>,
}

fn entry_epoch_millis(entry: &DirEntry) -> i64 {
    if let Ok(meta) = entry.metadata() {
        if let Ok(modified) = meta.modified() {
            if let Ok(dur) = modified.duration_since(UNIX_EPOCH) {
                return dur.as_millis() as i64;
            }
        }
    }
    0
}

fn path_epoch_millis(path: &Path) -> i64 {
    if let Ok(meta) = path.metadata() {
        if let Ok(modified) = meta.modified() {
            if let Ok(dur) = modified.duration_since(UNIX_EPOCH) {
                return dur.as_millis() as i64;
            }
        }
    }
    0
}

fn calc_folder_size(folder: &Path, max_depth: usize) -> u64 {
    let mut total = 0u64;
    for entry in WalkDir::new(folder)
        .max_depth(max_depth)
        .into_iter()
        .filter_map(|e| e.ok())
    {
        if entry.file_type().is_file() {
            if let Ok(meta) = entry.metadata() {
                total += meta.len();
            }
        }
    }
    total
}

fn calc_folder_effective_modified(folder: &Path, default_time: i64, max_depth: usize) -> i64 {
    let mut max_time = default_time;
    for entry in WalkDir::new(folder)
        .max_depth(max_depth)
        .into_iter()
        .filter_map(|e| e.ok())
    {
        let time = path_epoch_millis(entry.path());
        if time > max_time {
            max_time = time;
        }
    }
    max_time
}

pub fn fast_list_dir(
    dir_path: &str,
    calc_folder_sizes: bool,
    calc_effective_dates: bool,
) -> ListDirResult {
    let dir = Path::new(dir_path);
    if !dir.is_dir() {
        return ListDirResult {
            success: false,
            entries: None,
            error: Some("Not a directory".to_string()),
        };
    }

    let read_res = match fs::read_dir(dir) {
        Ok(r) => r,
        Err(e) => {
            return ListDirResult {
                success: false,
                entries: None,
                error: Some(e.to_string()),
            };
        }
    };

    let mut entries = Vec::new();

    for entry_res in read_res {
        let entry = match entry_res {
            Ok(e) => e,
            Err(_) => continue,
        };

        let file_type = match entry.file_type() {
            Ok(ft) => ft,
            Err(_) => continue,
        };

        let name = entry.file_name().to_string_lossy().to_string();
        let path = entry.path().to_string_lossy().to_string();
        let is_dir = file_type.is_dir();
        let is_hidden = name.starts_with('.');
        let modified = entry_epoch_millis(&entry);

        let (size, effective_time, item_count, subfolder_count) = if is_dir {
            // Shallow count of direct children
            let mut items = 0u32;
            let mut subfolders = 0u32;
            if let Ok(children) = fs::read_dir(entry.path()) {
                for child_res in children {
                    if let Ok(child) = child_res {
                        items += 1;
                        if let Ok(cft) = child.file_type() {
                            if cft.is_dir() {
                                subfolders += 1;
                            }
                        }
                    }
                }
            }

            let sz = if calc_folder_sizes {
                calc_folder_size(&entry.path(), 2)
            } else {
                0
            };

            let eff_time = if calc_effective_dates {
                calc_folder_effective_modified(&entry.path(), modified, 2)
            } else {
                modified
            };

            (sz, eff_time, items, subfolders)
        } else {
            let sz = entry.metadata().map(|m| m.len()).unwrap_or(0);
            (sz, modified, 0, 0)
        };

        entries.push(NativeDirEntry {
            name,
            path,
            is_directory: is_dir,
            size,
            last_modified: effective_time,
            item_count,
            subfolder_count,
            is_hidden,
        });
    }

    ListDirResult {
        success: true,
        entries: Some(entries),
        error: None,
    }
}

pub fn fast_search(
    root_path: &str,
    query: &str,
    max_results: usize,
    max_depth: usize,
    allowed_exts: Option<Vec<String>>,
) -> SearchResult {
    let root = Path::new(root_path);
    if !root.exists() {
        return SearchResult {
            success: false,
            entries: None,
            error: Some("Root path not found".to_string()),
        };
    }

    let query_lower = query.to_lowercase();
    let exts_lower: Option<std::collections::HashSet<String>> = allowed_exts.map(|list| {
        list.into_iter()
            .map(|ext| ext.trim_start_matches('.').to_lowercase())
            .collect()
    });

    let mut results = Vec::new();

    for entry in WalkDir::new(root)
        .max_depth(max_depth)
        .into_iter()
        .filter_entry(|e| {
            // Prune hidden folders completely
            let name = e.file_name().to_string_lossy();
            !name.starts_with('.')
        })
        .filter_map(|e| e.ok())
    {
        if results.len() >= max_results {
            break;
        }

        // Skip the root folder itself
        if entry.depth() == 0 {
            continue;
        }

        let name = entry.file_name().to_string_lossy();
        let is_dir = entry.file_type().is_dir();
        let ext = if is_dir {
            String::new()
        } else {
            entry
                .path()
                .extension()
                .and_then(|e| e.to_str())
                .unwrap_or("")
                .to_lowercase()
        };

        let matches_type = is_dir
            || match &exts_lower {
                Some(set) => set.contains(&ext),
                None => true,
            };

        if matches_type && name.to_lowercase().contains(&query_lower) {
            let meta = entry.metadata().ok();
            let size = if is_dir {
                0
            } else {
                meta.as_ref().map(|m| m.len()).unwrap_or(0)
            };
            let modified = meta
                .and_then(|m| m.modified().ok())
                .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
                .map(|d| d.as_millis() as i64)
                .unwrap_or(0);

            results.push(NativeSearchEntry {
                name: name.to_string(),
                path: entry.path().to_string_lossy().to_string(),
                is_directory: is_dir,
                size,
                last_modified: modified,
                extension: ext,
            });
        }
    }

    SearchResult {
        success: true,
        entries: Some(results),
        error: None,
    }
}
