use std::fs::{self, File};
use std::io::{self, Error, ErrorKind};
use std::path::Path;
use std::time::SystemTime;

pub fn copy_file_fast(src_str: &str, dst_str: &str, overwrite: bool) -> io::Result<u64> {
    let src = Path::new(src_str);
    let dst = Path::new(dst_str);

    if !src.is_file() {
        return Err(Error::new(ErrorKind::NotFound, "Source file not found"));
    }

    if dst.exists() {
        if !overwrite {
            return Err(Error::new(ErrorKind::AlreadyExists, "Destination file already exists"));
        }
        if dst.is_dir() {
            let _ = fs::remove_dir_all(dst);
        } else {
            let _ = fs::remove_file(dst);
        }
    }

    if let Some(parent) = dst.parent() {
        if !parent.exists() {
            fs::create_dir_all(parent)?;
        }
    }

    // On Linux/Android, fs::copy leverages copy_file_range / sendfile64
    let bytes = fs::copy(src, dst)?;

    // Preserve original timestamp
    if let Ok(meta) = src.metadata() {
        if let Ok(mtime) = meta.modified() {
            let _ = set_file_mtime(dst, mtime);
        }
    }

    Ok(bytes)
}

pub fn move_file_fast(src_str: &str, dst_str: &str, overwrite: bool) -> io::Result<()> {
    let src = Path::new(src_str);
    let dst = Path::new(dst_str);

    if !src.exists() {
        return Err(Error::new(ErrorKind::NotFound, "Source not found"));
    }

    if dst.exists() {
        if !overwrite {
            return Err(Error::new(ErrorKind::AlreadyExists, "Destination already exists"));
        }
        if dst.is_dir() {
            let _ = fs::remove_dir_all(dst);
        } else {
            let _ = fs::remove_file(dst);
        }
    }

    if let Some(parent) = dst.parent() {
        if !parent.exists() {
            fs::create_dir_all(parent)?;
        }
    }

    // Try atomic rename first (instant if same mount/partition)
    if fs::rename(src, dst).is_ok() {
        return Ok(());
    }

    // Fallback: cross-partition copy + delete
    if src.is_dir() {
        // Recursive copy directory
        copy_dir_recursive(src, dst)?;
        let _ = fs::remove_dir_all(src);
    } else {
        copy_file_fast(src_str, dst_str, overwrite)?;
        let _ = fs::remove_file(src);
    }

    Ok(())
}

fn copy_dir_recursive(src: &Path, dst: &Path) -> io::Result<()> {
    if !dst.exists() {
        fs::create_dir_all(dst)?;
    }

    for entry_res in fs::read_dir(src)? {
        let entry = entry_res?;
        let entry_type = entry.file_type()?;
        let target = dst.join(entry.file_name());

        if entry_type.is_dir() {
            copy_dir_recursive(&entry.path(), &target)?;
        } else {
            let _ = fs::copy(entry.path(), &target);
        }
    }
    Ok(())
}

fn set_file_mtime(path: &Path, mtime: SystemTime) -> io::Result<()> {
    if let Ok(file) = File::options().write(true).open(path) {
        let _ = file.set_modified(mtime);
    }
    Ok(())
}
