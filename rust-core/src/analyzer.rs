use std::collections::HashMap;
use std::fs::{self, File};
use std::io::{Read, Seek, SeekFrom};
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use rayon::prelude::*;
use serde::Serialize;
use walkdir::WalkDir;

#[derive(Serialize)]
pub struct LargeFileEntry {
    pub path: String,
    pub name: String,
    #[serde(rename = "relativeDir")]
    pub relative_dir: String,
    #[serde(rename = "sizeBytes")]
    pub size_bytes: u64,
    pub extension: String,
    #[serde(rename = "lastModified")]
    pub last_modified: u64,
}

#[derive(Serialize)]
pub struct DuplicateEntry {
    pub path: String,
    pub name: String,
    #[serde(rename = "relativeDir")]
    pub relative_dir: String,
    #[serde(rename = "sizeBytes")]
    pub size_bytes: u64,
    #[serde(rename = "lastModified")]
    pub last_modified: u64,
}

#[derive(Serialize)]
pub struct DuplicateGroup {
    pub hash: String,
    #[serde(rename = "fileSize")]
    pub file_size: u64,
    #[serde(rename = "wastedBytes")]
    pub wasted_bytes: u64,
    pub entries: Vec<DuplicateEntry>,
}

#[derive(Serialize)]
pub struct AnalysisResult {
    #[serde(rename = "imgBytes")]
    pub img_bytes: u64,
    #[serde(rename = "audioBytes")]
    pub audio_bytes: u64,
    #[serde(rename = "videoBytes")]
    pub video_bytes: u64,
    #[serde(rename = "docBytes")]
    pub doc_bytes: u64,
    #[serde(rename = "archiveBytes")]
    pub archive_bytes: u64,
    #[serde(rename = "otherBytes")]
    pub other_bytes: u64,
    #[serde(rename = "largeFiles")]
    pub large_files: Vec<LargeFileEntry>,
    #[serde(rename = "duplicateGroups")]
    pub duplicate_groups: Vec<DuplicateGroup>,
    #[serde(rename = "duplicateWastedBytes")]
    pub duplicate_wasted_bytes: u64,
}

#[derive(Default)]
struct ThreadScanAcc {
    img_bytes: u64,
    audio_bytes: u64,
    video_bytes: u64,
    doc_bytes: u64,
    archive_bytes: u64,
    other_bytes: u64,
    large_files: Vec<LargeFileEntry>,
    size_buckets: HashMap<u64, Vec<PathBuf>>,
}

const PARTIAL_CHUNK_SIZE: u64 = 4096;

#[inline]
fn is_image(ext: &str) -> bool {
    matches!(ext, "jpg" | "jpeg" | "png" | "webp" | "gif" | "bmp" | "heic" | "heif" | "svg")
}

#[inline]
fn is_audio(ext: &str) -> bool {
    matches!(ext, "mp3" | "m4a" | "aac" | "wav" | "flac" | "ogg" | "opus" | "wma")
}

#[inline]
fn is_video(ext: &str) -> bool {
    matches!(ext, "mp4" | "mkv" | "webm" | "avi" | "mov" | "3gp" | "flv")
}

#[inline]
fn is_document(ext: &str) -> bool {
    matches!(ext, "pdf" | "doc" | "docx" | "xls" | "xlsx" | "ppt" | "pptx" | "txt")
}

#[inline]
fn is_archive(ext: &str) -> bool {
    matches!(ext, "zip" | "rar" | "7z" | "tar" | "gz" | "bz2" | "xz")
}

fn compute_partial_hash(path: &Path, file_size: u64) -> Option<[u8; 32]> {
    let mut file = File::open(path).ok()?;
    let mut hasher = blake3::Hasher::new();
    let mut buf = [0u8; PARTIAL_CHUNK_SIZE as usize];

    let first_to_read = std::cmp::min(PARTIAL_CHUNK_SIZE, file_size) as usize;
    file.read_exact(&mut buf[..first_to_read]).ok()?;
    hasher.update(&buf[..first_to_read]);

    if file_size > PARTIAL_CHUNK_SIZE * 2 {
        file.seek(SeekFrom::End(-(PARTIAL_CHUNK_SIZE as i64))).ok()?;
        file.read_exact(&mut buf).ok()?;
        hasher.update(&buf);
    }

    Some(*hasher.finalize().as_bytes())
}

fn compute_full_hash(path: &Path) -> Option<String> {
    let mut file = File::open(path).ok()?;
    let mut hasher = blake3::Hasher::new();
    let mut buf = [0u8; 65536];

    loop {
        match file.read(&mut buf) {
            Ok(0) => break,
            Ok(n) => hasher.update(&buf[..n]),
            Err(_) => return None,
        };
    }

    Some(hasher.finalize().to_hex().to_string())
}

#[inline]
fn process_file_entry(
    path: &Path,
    len: u64,
    last_modified: u64,
    file_name: &str,
    root: &Path,
    max_duplicate_hash_bytes: u64,
    acc: &mut ThreadScanAcc,
) {
    let ext = path
        .extension()
        .and_then(|s| s.to_str())
        .unwrap_or("")
        .to_lowercase();

    if is_image(&ext) {
        acc.img_bytes += len;
    } else if is_audio(&ext) {
        acc.audio_bytes += len;
    } else if is_video(&ext) {
        acc.video_bytes += len;
    } else if is_document(&ext) {
        acc.doc_bytes += len;
    } else if is_archive(&ext) {
        acc.archive_bytes += len;
    } else {
        acc.other_bytes += len;
    }

    // Large file threshold: >= 10 MB
    if len >= 10 * 1024 * 1024 {
        let parent = path.parent().unwrap_or(root);
        let rel_dir = match parent.strip_prefix(root) {
            Ok(p) if p.as_os_str().is_empty() => "/".to_string(),
            Ok(p) => format!("/{}", p.to_string_lossy()),
            Err(_) => "/".to_string(),
        };

        acc.large_files.push(LargeFileEntry {
            path: path.to_string_lossy().to_string(),
            name: file_name.to_string(),
            relative_dir: rel_dir,
            size_bytes: len,
            extension: ext,
            last_modified,
        });
    }

    if len > 0 && len <= max_duplicate_hash_bytes {
        acc.size_buckets.entry(len).or_default().push(path.to_path_buf());
    }
}

pub fn analyze_storage(root_path: &str, max_duplicate_hash_bytes: u64) -> AnalysisResult {
    let root = Path::new(root_path);

    // Read top-level items in storage root
    let mut top_dirs = Vec::new();
    let mut root_acc = ThreadScanAcc::default();

    if let Ok(entries) = fs::read_dir(root) {
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
                top_dirs.push(path);
            } else if file_type.is_file() {
                if let Ok(meta) = entry.metadata() {
                    let len = meta.len();
                    let mtime = meta
                        .modified()
                        .ok()
                        .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                        .map(|d| d.as_millis() as u64)
                        .unwrap_or(0);
                    process_file_entry(
                        &path,
                        len,
                        mtime,
                        &name_str,
                        root,
                        max_duplicate_hash_bytes,
                        &mut root_acc,
                    );
                }
            }
        }
    }

    // Fan out top-level directories concurrently across all CPU cores with Rayon
    let branch_accs: Vec<ThreadScanAcc> = top_dirs
        .into_par_iter()
        .map(|dir_path| {
            let mut acc = ThreadScanAcc::default();

            for entry in WalkDir::new(&dir_path)
                .min_depth(1)
                .into_iter()
                .filter_entry(|e| {
                    let name = e.file_name().to_string_lossy();
                    if name.starts_with('.') {
                        return false;
                    }
                    if e.file_type().is_dir() {
                        // Skip Android/data and Android/obb which are restricted on Android 11+
                        // and contain hundreds of thousands of app cache files
                        if name == "data" || name == "obb" {
                            if let Some(parent) = e.path().parent() {
                                if parent.file_name().map(|n| n == "Android").unwrap_or(false) {
                                    return false;
                                }
                            }
                        }
                    }
                    true
                })
                .filter_map(|e| e.ok())
            {
                if entry.file_type().is_file() {
                    let path = entry.path();
                    let metadata = match entry.metadata() {
                        Ok(m) => m,
                        Err(_) => continue,
                    };
                    let len = metadata.len();
                    let mtime = metadata
                        .modified()
                        .ok()
                        .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                        .map(|d| d.as_millis() as u64)
                        .unwrap_or(0);
                    let file_name = entry.file_name().to_string_lossy();

                    process_file_entry(
                        path,
                        len,
                        mtime,
                        &file_name,
                        root,
                        max_duplicate_hash_bytes,
                        &mut acc,
                    );
                }
            }

            acc
        })
        .collect();

    // Merge results from all parallel branches into root_acc
    for branch in branch_accs {
        root_acc.img_bytes += branch.img_bytes;
        root_acc.audio_bytes += branch.audio_bytes;
        root_acc.video_bytes += branch.video_bytes;
        root_acc.doc_bytes += branch.doc_bytes;
        root_acc.archive_bytes += branch.archive_bytes;
        root_acc.other_bytes += branch.other_bytes;
        root_acc.large_files.extend(branch.large_files);

        for (size, files) in branch.size_buckets {
            root_acc.size_buckets.entry(size).or_default().extend(files);
        }
    }

    // Sort large files descending by size
    root_acc.large_files.sort_by(|a, b| b.size_bytes.cmp(&a.size_bytes));

    // Parallel multi-tiered duplicate detection
    let candidate_sizes: Vec<(u64, Vec<PathBuf>)> = root_acc
        .size_buckets
        .into_iter()
        .filter(|(_, files)| files.len() >= 2)
        .collect();

    let duplicate_groups = Mutex::new(Vec::new());

    candidate_sizes.into_par_iter().for_each(|(size, files)| {
        let mut partial_groups: HashMap<[u8; 32], Vec<PathBuf>> = HashMap::new();
        for f in files {
            if let Some(ph) = compute_partial_hash(&f, size) {
                partial_groups.entry(ph).or_default().push(f);
            }
        }

        for (_, p_files) in partial_groups {
            if p_files.len() < 2 {
                continue;
            }

            let mut full_groups: HashMap<String, Vec<PathBuf>> = HashMap::new();
            for f in p_files {
                if let Some(fh) = compute_full_hash(&f) {
                    full_groups.entry(fh).or_default().push(f);
                }
            }

            for (hash, matched_files) in full_groups {
                if matched_files.len() < 2 {
                    continue;
                }

                let entries: Vec<DuplicateEntry> = matched_files
                    .into_iter()
                    .filter_map(|f| {
                        let meta = f.metadata().ok()?;
                        let name = f.file_name()?.to_string_lossy().to_string();
                        let parent = f.parent().unwrap_or(root);
                        let rel_dir = match parent.strip_prefix(root) {
                            Ok(p) if p.as_os_str().is_empty() => "/".to_string(),
                            Ok(p) => format!("/{}", p.to_string_lossy()),
                            Err(_) => "/".to_string(),
                        };
                        let last_modified = meta
                            .modified()
                            .ok()
                            .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                            .map(|d| d.as_millis() as u64)
                            .unwrap_or(0);

                        Some(DuplicateEntry {
                            path: f.to_string_lossy().to_string(),
                            name,
                            relative_dir: rel_dir,
                            size_bytes: size,
                            last_modified,
                        })
                    })
                    .collect();

                if entries.len() >= 2 {
                    let wasted_bytes = (entries.len() as u64 - 1) * size;
                    let mut lock = duplicate_groups.lock().unwrap();
                    lock.push(DuplicateGroup {
                        hash,
                        file_size: size,
                        wasted_bytes,
                        entries,
                    });
                }
            }
        }
    });

    let mut final_groups = duplicate_groups.into_inner().unwrap();
    final_groups.sort_by(|a, b| b.wasted_bytes.cmp(&a.wasted_bytes));
    let duplicate_wasted_bytes: u64 = final_groups.iter().map(|g| g.wasted_bytes).sum();

    AnalysisResult {
        img_bytes: root_acc.img_bytes,
        audio_bytes: root_acc.audio_bytes,
        video_bytes: root_acc.video_bytes,
        doc_bytes: root_acc.doc_bytes,
        archive_bytes: root_acc.archive_bytes,
        other_bytes: root_acc.other_bytes,
        large_files: root_acc.large_files,
        duplicate_groups: final_groups,
        duplicate_wasted_bytes,
    }
}
