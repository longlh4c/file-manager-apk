use std::collections::HashMap;
use std::fs::File;
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

const PARTIAL_CHUNK_SIZE: u64 = 4096;

fn is_image(ext: &str) -> bool {
    matches!(ext, "jpg" | "jpeg" | "png" | "webp" | "gif" | "bmp" | "heic" | "heif" | "svg")
}

fn is_audio(ext: &str) -> bool {
    matches!(ext, "mp3" | "m4a" | "aac" | "wav" | "flac" | "ogg" | "opus" | "wma")
}

fn is_video(ext: &str) -> bool {
    matches!(ext, "mp4" | "mkv" | "webm" | "avi" | "mov" | "3gp" | "flv")
}

fn is_document(ext: &str) -> bool {
    matches!(ext, "pdf" | "doc" | "docx" | "xls" | "xlsx" | "ppt" | "pptx" | "txt")
}

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

pub fn analyze_storage(root_path: &str, max_duplicate_hash_bytes: u64) -> AnalysisResult {
    let root = Path::new(root_path);
    let mut img_bytes = 0u64;
    let mut audio_bytes = 0u64;
    let mut video_bytes = 0u64;
    let mut doc_bytes = 0u64;
    let mut archive_bytes = 0u64;
    let mut other_bytes = 0u64;

    let mut large_files = Vec::new();
    let mut size_buckets: HashMap<u64, Vec<PathBuf>> = HashMap::new();

    for entry in WalkDir::new(root)
        .min_depth(1)
        .into_iter()
        .filter_entry(|e| {
            let name = e.file_name().to_string_lossy();
            !name.starts_with('.')
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
            let ext = path
                .extension()
                .and_then(|s| s.to_str())
                .unwrap_or("")
                .to_lowercase();

            if is_image(&ext) {
                img_bytes += len;
            } else if is_audio(&ext) {
                audio_bytes += len;
            } else if is_video(&ext) {
                video_bytes += len;
            } else if is_document(&ext) {
                doc_bytes += len;
            } else if is_archive(&ext) {
                archive_bytes += len;
            } else {
                other_bytes += len;
            }

            if len >= 10 * 1024 * 1024 {
                let name = entry.file_name().to_string_lossy().to_string();
                let parent = path.parent().unwrap_or(root);
                let rel_dir = match parent.strip_prefix(root) {
                    Ok(p) if p.as_os_str().is_empty() => "/".to_string(),
                    Ok(p) => format!("/{}", p.to_string_lossy()),
                    Err(_) => "/".to_string(),
                };
                let last_modified = metadata
                    .modified()
                    .ok()
                    .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                    .map(|d| d.as_millis() as u64)
                    .unwrap_or(0);

                large_files.push(LargeFileEntry {
                    path: path.to_string_lossy().to_string(),
                    name,
                    relative_dir: rel_dir,
                    size_bytes: len,
                    extension: ext,
                    last_modified,
                });
            }

            if len > 0 && len <= max_duplicate_hash_bytes {
                size_buckets.entry(len).or_default().push(path.to_path_buf());
            }
        }
    }

    large_files.sort_by(|a, b| b.size_bytes.cmp(&a.size_bytes));

    let candidate_sizes: Vec<(u64, Vec<PathBuf>)> = size_buckets
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
        img_bytes,
        audio_bytes,
        video_bytes,
        doc_bytes,
        archive_bytes,
        other_bytes,
        large_files,
        duplicate_groups: final_groups,
        duplicate_wasted_bytes,
    }
}
