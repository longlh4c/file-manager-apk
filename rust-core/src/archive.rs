use std::fs::{self, File};
use std::io::{self, BufReader, BufWriter};
use std::path::Path;
use serde::{Deserialize, Serialize};
use walkdir::WalkDir;
use zip::write::SimpleFileOptions;
use zip::{ZipArchive, ZipWriter};

#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct ArchiveEntryItem {
    pub path: String,
    pub size: u64,
    pub is_directory: bool,
    pub last_modified: i64,
}

#[derive(Serialize, Deserialize, Debug)]
pub struct ArchiveResult {
    pub success: bool,
    pub entries: Option<Vec<ArchiveEntryItem>>,
    pub extracted_paths: Option<Vec<String>>,
    pub total_bytes: Option<u64>,
    pub error_code: Option<String>,
    pub error_message: Option<String>,
}

impl ArchiveResult {
    fn ok_entries(entries: Vec<ArchiveEntryItem>) -> Self {
        Self {
            success: true,
            entries: Some(entries),
            extracted_paths: None,
            total_bytes: None,
            error_code: None,
            error_message: None,
        }
    }

    fn ok_extracted(paths: Vec<String>) -> Self {
        Self {
            success: true,
            entries: None,
            extracted_paths: Some(paths),
            total_bytes: None,
            error_code: None,
            error_message: None,
        }
    }

    fn ok_compressed(total_bytes: u64) -> Self {
        Self {
            success: true,
            entries: None,
            extracted_paths: None,
            total_bytes: Some(total_bytes),
            error_code: None,
            error_message: None,
        }
    }

    fn err(code: &str, message: &str) -> Self {
        Self {
            success: false,
            entries: None,
            extracted_paths: None,
            total_bytes: None,
            error_code: Some(code.to_string()),
            error_message: Some(message.to_string()),
        }
    }
}

fn zip_dt_to_millis(dt: zip::DateTime) -> i64 {
    let year = dt.year() as i32;
    let month = dt.month() as u32;
    let day = dt.day() as u32;
    let hour = dt.hour() as u32;
    let minute = dt.minute() as u32;
    let second = dt.second() as u32;
    chrono::NaiveDate::from_ymd_opt(year, month, day)
        .and_then(|d| d.and_hms_opt(hour, minute, second))
        .map(|dt| dt.and_utc().timestamp_millis())
        .unwrap_or(0)
}

fn is_inside_target(path: &Path, target_canonical: &Path) -> bool {
    let mut current = path.to_path_buf();
    while !current.exists() {
        if let Some(parent) = current.parent() {
            current = parent.to_path_buf();
        } else {
            break;
        }
    }
    if let Ok(can) = current.canonicalize() {
        can.starts_with(target_canonical)
    } else {
        false
    }
}

pub fn list_archive_entries(archive_path: &str, password: Option<&str>) -> ArchiveResult {
    let path = Path::new(archive_path);
    if !path.is_file() {
        return ArchiveResult::err("NOT_FOUND", "Archive file not found");
    }

    let ext = path.extension().and_then(|e| e.to_str()).unwrap_or("").to_lowercase();
    match ext.as_str() {
        "7z" => list_7z_entries(archive_path, password),
        _ => list_zip_entries(archive_path, password),
    }
}

fn list_zip_entries(archive_path: &str, password: Option<&str>) -> ArchiveResult {
    let file = match File::open(archive_path) {
        Ok(f) => f,
        Err(e) => return ArchiveResult::err("IO_ERROR", &e.to_string()),
    };

    let mut zip = match ZipArchive::new(BufReader::new(file)) {
        Ok(z) => z,
        Err(e) => return ArchiveResult::err("FORMAT_ERROR", &e.to_string()),
    };

    let mut entries = Vec::with_capacity(zip.len());
    for i in 0..zip.len() {
        let entry_res = if let Some(pwd) = password {
            zip.by_index_decrypt(i, pwd.as_bytes())
        } else {
            zip.by_index(i)
        };

        match entry_res {
            Ok(entry) => {
                let name = entry.name().trim_end_matches('/').to_string();
                if name.is_empty() {
                    continue;
                }
                let is_dir = entry.is_dir();
                let size = if is_dir { 0 } else { entry.size() };
                let last_modified = entry.last_modified().map(zip_dt_to_millis).unwrap_or(0);

                entries.push(ArchiveEntryItem {
                    path: name,
                    size,
                    is_directory: is_dir,
                    last_modified,
                });
            }
            Err(zip::result::ZipError::InvalidPassword) => {
                return if password.is_none() {
                    ArchiveResult::err("PASSWORD_REQUIRED", "Password required for this archive")
                } else {
                    ArchiveResult::err("INVALID_PASSWORD", "Incorrect password for archive")
                };
            }
            Err(e) => {
                let msg = e.to_string().to_lowercase();
                if msg.contains("password") {
                    return ArchiveResult::err("INVALID_PASSWORD", &e.to_string());
                }
                return ArchiveResult::err("CORRUPT_ARCHIVE", &e.to_string());
            }
        }
    }

    ArchiveResult::ok_entries(entries)
}

fn list_7z_entries(archive_path: &str, password: Option<&str>) -> ArchiveResult {
    let pwd = password
        .map(sevenz_rust::Password::from)
        .unwrap_or_else(sevenz_rust::Password::empty);

    let reader = match sevenz_rust::SevenZReader::open(archive_path, pwd) {
        Ok(r) => r,
        Err(e) => {
            let msg = e.to_string().to_lowercase();
            if msg.contains("password") {
                return if password.is_none() {
                    ArchiveResult::err("PASSWORD_REQUIRED", "Password required for this 7z archive")
                } else {
                    ArchiveResult::err("INVALID_PASSWORD", "Incorrect password for 7z archive")
                };
            }
            return ArchiveResult::err("FORMAT_ERROR", &e.to_string());
        }
    };

    let files = &reader.archive().files;
    let mut entries = Vec::with_capacity(files.len());
    for file in files {
        let name = file.name().trim_end_matches('/').to_string();
        if name.is_empty() {
            continue;
        }
        let is_dir = file.is_directory();
        let size = if is_dir { 0 } else { file.size() };
        let last_modified = if file.has_last_modified_date {
            let st: std::time::SystemTime = file.last_modified_date.into();
            st.duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_millis() as i64)
                .unwrap_or(0)
        } else {
            0
        };

        entries.push(ArchiveEntryItem {
            path: name,
            size,
            is_directory: is_dir,
            last_modified,
        });
    }

    ArchiveResult::ok_entries(entries)
}

pub fn extract_archive(
    archive_path: &str,
    target_dir: &str,
    password: Option<&str>,
    selected_paths: Option<Vec<String>>,
) -> ArchiveResult {
    let path = Path::new(archive_path);
    if !path.is_file() {
        return ArchiveResult::err("NOT_FOUND", "Archive file not found");
    }

    let target = Path::new(target_dir);
    if let Err(e) = fs::create_dir_all(target) {
        return ArchiveResult::err("IO_ERROR", &e.to_string());
    }

    let target_canonical = match target.canonicalize() {
        Ok(c) => c,
        Err(e) => return ArchiveResult::err("IO_ERROR", &e.to_string()),
    };

    let ext = path.extension().and_then(|e| e.to_str()).unwrap_or("").to_lowercase();
    match ext.as_str() {
        "7z" => extract_7z(archive_path, target, &target_canonical, password, selected_paths),
        _ => extract_zip(archive_path, target, &target_canonical, password, selected_paths),
    }
}

fn extract_zip(
    archive_path: &str,
    target: &Path,
    target_canonical: &Path,
    password: Option<&str>,
    selected_paths: Option<Vec<String>>,
) -> ArchiveResult {
    let file = match File::open(archive_path) {
        Ok(f) => f,
        Err(e) => return ArchiveResult::err("IO_ERROR", &e.to_string()),
    };

    let mut zip = match ZipArchive::new(BufReader::new(file)) {
        Ok(z) => z,
        Err(e) => return ArchiveResult::err("FORMAT_ERROR", &e.to_string()),
    };

    let mut extracted = Vec::new();
    let selected_set: Option<std::collections::HashSet<String>> = selected_paths.map(|paths| {
        paths.into_iter().map(|p| p.trim_matches('/').to_string()).collect()
    });

    for i in 0..zip.len() {
        let entry_res = if let Some(pwd) = password {
            zip.by_index_decrypt(i, pwd.as_bytes())
        } else {
            zip.by_index(i)
        };

        let mut entry = match entry_res {
            Ok(e) => e,
            Err(zip::result::ZipError::InvalidPassword) => {
                return if password.is_none() {
                    ArchiveResult::err("PASSWORD_REQUIRED", "Password required for this archive")
                } else {
                    ArchiveResult::err("INVALID_PASSWORD", "Incorrect password for archive")
                };
            }
            Err(e) => return ArchiveResult::err("EXTRACT_ERROR", &e.to_string()),
        };

        let name = entry.name().trim_matches('/').to_string();
        if name.is_empty() {
            continue;
        }

        if let Some(ref sel) = selected_set {
            let matched = sel.iter().any(|s| name == *s || name.starts_with(&format!("{s}/")));
            if !matched {
                continue;
            }
        }

        let dest = target.join(&name);
        if !is_inside_target(&dest, target_canonical) {
            return ArchiveResult::err("SECURITY_ERROR", &format!("Zip slip detected in entry: {name}"));
        }

        if entry.is_dir() {
            if let Err(e) = fs::create_dir_all(&dest) {
                return ArchiveResult::err("IO_ERROR", &e.to_string());
            }
        } else {
            if let Some(parent) = dest.parent() {
                if let Err(e) = fs::create_dir_all(parent) {
                    return ArchiveResult::err("IO_ERROR", &e.to_string());
                }
            }
            let mut out = match File::create(&dest) {
                Ok(f) => BufWriter::new(f),
                Err(e) => return ArchiveResult::err("IO_ERROR", &e.to_string()),
            };
            if let Err(e) = io::copy(&mut entry, &mut out) {
                return ArchiveResult::err("IO_ERROR", &e.to_string());
            }
            extracted.push(dest.to_string_lossy().to_string());
        }
    }

    ArchiveResult::ok_extracted(extracted)
}

fn extract_7z(
    archive_path: &str,
    target: &Path,
    target_canonical: &Path,
    password: Option<&str>,
    selected_paths: Option<Vec<String>>,
) -> ArchiveResult {
    let pwd = password
        .map(sevenz_rust::Password::from)
        .unwrap_or_else(sevenz_rust::Password::empty);

    let mut reader = match sevenz_rust::SevenZReader::open(archive_path, pwd) {
        Ok(r) => r,
        Err(e) => {
            let msg = e.to_string().to_lowercase();
            if msg.contains("password") {
                return if password.is_none() {
                    ArchiveResult::err("PASSWORD_REQUIRED", "Password required for this 7z archive")
                } else {
                    ArchiveResult::err("INVALID_PASSWORD", "Incorrect password for 7z archive")
                };
            }
            return ArchiveResult::err("FORMAT_ERROR", &e.to_string());
        }
    };

    let selected_set: Option<std::collections::HashSet<String>> = selected_paths.map(|paths| {
        paths.into_iter().map(|p| p.trim_matches('/').to_string()).collect()
    });

    let mut extracted = Vec::new();

    let res = reader.for_each_entries(|entry, entry_reader| {
        let name = entry.name().trim_matches('/').to_string();
        if name.is_empty() {
            return Ok(true);
        }

        if let Some(ref sel) = selected_set {
            let matched = sel.iter().any(|s| name == *s || name.starts_with(&format!("{s}/")));
            if !matched {
                return Ok(true);
            }
        }

        let dest = target.join(&name);
        if !is_inside_target(&dest, target_canonical) {
            return Err(sevenz_rust::Error::other(format!("Zip slip detected in entry: {name}")));
        }

        if entry.is_directory() {
            if let Err(e) = fs::create_dir_all(&dest) {
                return Err(sevenz_rust::Error::io(e));
            }
        } else {
            if let Some(parent) = dest.parent() {
                if let Err(e) = fs::create_dir_all(parent) {
                    return Err(sevenz_rust::Error::io(e));
                }
            }
            let mut out = match File::create(&dest) {
                Ok(f) => BufWriter::new(f),
                Err(e) => return Err(sevenz_rust::Error::io(e)),
            };
            if let Err(e) = io::copy(entry_reader, &mut out) {
                return Err(sevenz_rust::Error::io(e));
            }
            extracted.push(dest.to_string_lossy().to_string());
        }

        Ok(true)
    });

    match res {
        Ok(_) => ArchiveResult::ok_extracted(extracted),
        Err(e) => ArchiveResult::err("EXTRACT_ERROR", &e.to_string()),
    }
}

pub fn compress_to_zip(source_paths: Vec<String>, target_zip_path: &str) -> ArchiveResult {
    let zip_file = match File::create(target_zip_path) {
        Ok(f) => f,
        Err(e) => return ArchiveResult::err("IO_ERROR", &e.to_string()),
    };

    let mut zip = ZipWriter::new(BufWriter::new(zip_file));
    let options = SimpleFileOptions::default()
        .compression_method(zip::CompressionMethod::Deflated)
        .unix_permissions(0o644);

    let mut total_bytes = 0u64;

    for path_str in source_paths {
        let base_path = Path::new(&path_str);
        if !base_path.exists() {
            continue;
        }

        let root_name = base_path
            .file_name()
            .and_then(|n| n.to_str())
            .unwrap_or("entry");

        if base_path.is_file() {
            if let Err(e) = zip.start_file(root_name, options) {
                return ArchiveResult::err("COMPRESS_ERROR", &e.to_string());
            }
            let mut file = match File::open(base_path) {
                Ok(f) => BufReader::new(f),
                Err(e) => return ArchiveResult::err("IO_ERROR", &e.to_string()),
            };
            match io::copy(&mut file, &mut zip) {
                Ok(bytes) => total_bytes += bytes,
                Err(e) => return ArchiveResult::err("IO_ERROR", &e.to_string()),
            }
        } else if base_path.is_dir() {
            for entry in WalkDir::new(base_path).into_iter().filter_map(|e| e.ok()) {
                let entry_path = entry.path();
                let relative = match entry_path.strip_prefix(base_path) {
                    Ok(r) => r,
                    Err(_) => continue,
                };
                let relative_str = relative.to_string_lossy().replace('\\', "/");
                let entry_in_zip = if relative_str.is_empty() {
                    root_name.to_string()
                } else {
                    format!("{root_name}/{relative_str}")
                };

                if entry.file_type().is_dir() {
                    let dir_entry_name = format!("{}/", entry_in_zip.trim_end_matches('/'));
                    let dir_options = SimpleFileOptions::default().unix_permissions(0o755);
                    let _ = zip.add_directory(dir_entry_name, dir_options);
                } else if entry.file_type().is_file() {
                    if let Err(e) = zip.start_file(&entry_in_zip, options) {
                        return ArchiveResult::err("COMPRESS_ERROR", &e.to_string());
                    }
                    let mut file = match File::open(entry_path) {
                        Ok(f) => BufReader::new(f),
                        Err(e) => return ArchiveResult::err("IO_ERROR", &e.to_string()),
                    };
                    match io::copy(&mut file, &mut zip) {
                        Ok(bytes) => total_bytes += bytes,
                        Err(e) => return ArchiveResult::err("IO_ERROR", &e.to_string()),
                    }
                }
            }
        }
    }

    if let Err(e) = zip.finish() {
        return ArchiveResult::err("COMPRESS_ERROR", &e.to_string());
    }

    ArchiveResult::ok_compressed(total_bytes)
}
