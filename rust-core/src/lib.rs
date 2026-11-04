pub mod analyzer;
pub mod image_engine;
pub mod archive;
pub mod scanner;
pub mod transfer;
pub mod hasher;
pub mod apk_parser;
pub mod doc_scanner;
pub mod audio_meta;
pub mod image_meta;
pub mod video_meta;

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jbyteArray, jint, jlong, jstring};

#[no_mangle]
pub extern "system" fn Java_com_antigravity_filemanager_core_NativeEngine_ping<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    let output = env
        .new_string("OwlFM Rust Core Engine is Active")
        .expect("Couldn't create java string");
    output.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_antigravity_filemanager_core_NativeEngine_scanStorageAnalysis<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    root_path: JString<'local>,
    max_duplicate_hash_bytes: jlong,
) -> jstring {
    let path: String = match env.get_string(&root_path) {
        Ok(s) => s.into(),
        Err(_) => {
            let err = env
                .new_string("{\"error\":\"Invalid root path string\"}")
                .unwrap();
            return err.into_raw();
        }
    };

    let result = analyzer::analyze_storage(&path, max_duplicate_hash_bytes as u64);
    let json = match serde_json::to_string(&result) {
        Ok(s) => s,
        Err(e) => format!("{{\"error\":\"{}\"}}", e),
    };

    let output = env
        .new_string(json)
        .unwrap_or_else(|_| env.new_string("{}").unwrap());
    output.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_antigravity_filemanager_core_NativeEngine_resizeImage<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    source_path: JString<'local>,
    target_path: JString<'local>,
    target_width: jint,
    target_height: jint,
    quality: jint,
    format_str: JString<'local>,
    orientation: jint,
) -> jlong {
    let src: String = match env.get_string(&source_path) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };
    let dst: String = match env.get_string(&target_path) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };
    let fmt: String = match env.get_string(&format_str) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };

    match image_engine::resize_image_file(
        &src,
        &dst,
        target_width as u32,
        target_height as u32,
        quality as u8,
        &fmt,
        orientation as u32,
    ) {
        Ok(bytes) => bytes as jlong,
        Err(e) => {
            log::error!("Rust resize error: {}", e);
            -1
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_antigravity_filemanager_core_NativeEngine_listArchiveEntries<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    archive_path: JString<'local>,
    password: JString<'local>,
) -> jstring {
    let path: String = match env.get_string(&archive_path) {
        Ok(s) => s.into(),
        Err(_) => {
            let err = env
                .new_string("{\"success\":false,\"error_code\":\"INVALID_ARG\",\"error_message\":\"Invalid path\"}")
                .unwrap();
            return err.into_raw();
        }
    };
    let pwd: Option<String> = if password.is_null() {
        None
    } else {
        env.get_string(&password).ok().map(|s| s.into())
    };

    let result = archive::list_archive_entries(&path, pwd.as_deref());
    let json = serde_json::to_string(&result).unwrap_or_else(|e| {
        format!(
            "{{\"success\":false,\"error_code\":\"JSON_ERROR\",\"error_message\":\"{}\"}}",
            e
        )
    });

    let output = env
        .new_string(json)
        .unwrap_or_else(|_| env.new_string("{}").unwrap());
    output.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_antigravity_filemanager_core_NativeEngine_extractArchive<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    archive_path: JString<'local>,
    target_dir: JString<'local>,
    password: JString<'local>,
    selected_paths_json: JString<'local>,
) -> jstring {
    let path: String = match env.get_string(&archive_path) {
        Ok(s) => s.into(),
        Err(_) => {
            let err = env
                .new_string("{\"success\":false,\"error_code\":\"INVALID_ARG\",\"error_message\":\"Invalid path\"}")
                .unwrap();
            return err.into_raw();
        }
    };
    let target: String = match env.get_string(&target_dir) {
        Ok(s) => s.into(),
        Err(_) => {
            let err = env
                .new_string("{\"success\":false,\"error_code\":\"INVALID_ARG\",\"error_message\":\"Invalid target\"}")
                .unwrap();
            return err.into_raw();
        }
    };
    let pwd: Option<String> = if password.is_null() {
        None
    } else {
        env.get_string(&password).ok().map(|s| s.into())
    };

    let selected_paths: Option<Vec<String>> = if selected_paths_json.is_null() {
        None
    } else {
        env.get_string(&selected_paths_json)
            .ok()
            .and_then(|s| serde_json::from_str(&s.to_string_lossy()).ok())
    };

    let result = archive::extract_archive(&path, &target, pwd.as_deref(), selected_paths);
    let json = serde_json::to_string(&result).unwrap_or_else(|e| {
        format!(
            "{{\"success\":false,\"error_code\":\"JSON_ERROR\",\"error_message\":\"{}\"}}",
            e
        )
    });

    let output = env
        .new_string(json)
        .unwrap_or_else(|_| env.new_string("{}").unwrap());
    output.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_antigravity_filemanager_core_NativeEngine_compressZip<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    source_paths_json: JString<'local>,
    target_zip_path: JString<'local>,
) -> jstring {
    let target: String = match env.get_string(&target_zip_path) {
        Ok(s) => s.into(),
        Err(_) => {
            let err = env
                .new_string("{\"success\":false,\"error_code\":\"INVALID_ARG\",\"error_message\":\"Invalid target path\"}")
                .unwrap();
            return err.into_raw();
        }
    };
    let sources: Vec<String> = match env.get_string(&source_paths_json) {
        Ok(s) => serde_json::from_str(&s.to_string_lossy()).unwrap_or_default(),
        Err(_) => Vec::new(),
    };

    let result = archive::compress_to_zip(sources, &target);
    let json = serde_json::to_string(&result).unwrap_or_else(|e| {
        format!(
            "{{\"success\":false,\"error_code\":\"JSON_ERROR\",\"error_message\":\"{}\"}}",
            e
        )
    });

    let output = env
        .new_string(json)
        .unwrap_or_else(|_| env.new_string("{}").unwrap());
    output.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_antigravity_filemanager_core_NativeEngine_listDirectory<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    dir_path: JString<'local>,
    calc_folder_sizes: jboolean,
    calc_effective_dates: jboolean,
) -> jstring {
    let path: String = match env.get_string(&dir_path) {
        Ok(s) => s.into(),
        Err(_) => {
            let err = env
                .new_string("{\"success\":false,\"error\":\"Invalid dir path\"}")
                .unwrap();
            return err.into_raw();
        }
    };

    let result = scanner::fast_list_dir(&path, calc_folder_sizes != 0, calc_effective_dates != 0);
    let json = serde_json::to_string(&result).unwrap_or_else(|e| {
        format!("{{\"success\":false,\"error\":\"{}\"}}", e)
    });

    let output = env
        .new_string(json)
        .unwrap_or_else(|_| env.new_string("{}").unwrap());
    output.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_antigravity_filemanager_core_NativeEngine_searchFiles<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    root_path: JString<'local>,
    query: JString<'local>,
    max_results: jint,
    max_depth: jint,
    allowed_exts_json: JString<'local>,
) -> jstring {
    let path: String = match env.get_string(&root_path) {
        Ok(s) => s.into(),
        Err(_) => {
            let err = env
                .new_string("{\"success\":false,\"error\":\"Invalid root path\"}")
                .unwrap();
            return err.into_raw();
        }
    };
    let q: String = match env.get_string(&query) {
        Ok(s) => s.into(),
        Err(_) => {
            let err = env
                .new_string("{\"success\":false,\"error\":\"Invalid query\"}")
                .unwrap();
            return err.into_raw();
        }
    };
    let exts: Option<Vec<String>> = if allowed_exts_json.is_null() {
        None
    } else {
        env.get_string(&allowed_exts_json)
            .ok()
            .and_then(|s| serde_json::from_str(&s.to_string_lossy()).ok())
    };

    let result = scanner::fast_search(&path, &q, max_results as usize, max_depth as usize, exts);
    let json = serde_json::to_string(&result).unwrap_or_else(|e| {
        format!("{{\"success\":false,\"error\":\"{}\"}}", e)
    });

    let output = env
        .new_string(json)
        .unwrap_or_else(|_| env.new_string("{}").unwrap());
    output.into_raw()
}


#[no_mangle]
pub extern "system" fn Java_com_antigravity_filemanager_core_NativeEngine_copyFileFast<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    src: JString<'local>,
    dst: JString<'local>,
    overwrite: jboolean,
) -> jlong {
    let src_str: String = match env.get_string(&src) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };
    let dst_str: String = match env.get_string(&dst) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };

    match transfer::copy_file_fast(&src_str, &dst_str, overwrite != 0) {
        Ok(bytes) => bytes as jlong,
        Err(_) => -1,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_antigravity_filemanager_core_NativeEngine_moveFileFast<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    src: JString<'local>,
    dst: JString<'local>,
    overwrite: jboolean,
) -> jboolean {
    let src_str: String = match env.get_string(&src) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    let dst_str: String = match env.get_string(&dst) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };

    match transfer::move_file_fast(&src_str, &dst_str, overwrite != 0) {
        Ok(_) => 1,
        Err(_) => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_antigravity_filemanager_core_NativeEngine_computeChecksums<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
    flags: jint,
) -> jstring {
    let path_str: String = match env.get_string(&path) {
        Ok(s) => s.into(),
        Err(_) => {
            let err = env.new_string("{\"error\":\"Invalid path\"}").unwrap();
            return err.into_raw();
        }
    };

    match hasher::compute_checksums(&path_str, flags as u32) {
        Ok(result) => {
            let json = serde_json::to_string(&result).unwrap_or_else(|_| "{}".to_string());
            let out = env.new_string(json).unwrap_or_else(|_| env.new_string("{}").unwrap());
            out.into_raw()
        }
        Err(e) => {
            let err_json = format!("{{\"error\":\"{}\"}}", e);
            let out = env.new_string(err_json).unwrap_or_else(|_| env.new_string("{}").unwrap());
            out.into_raw()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_antigravity_filemanager_core_NativeEngine_extractApkIcon<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    apk_path: JString<'local>,
) -> jbyteArray {
    let path: String = match env.get_string(&apk_path) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    match apk_parser::extract_apk_icon(&path) {
        Ok(Some(bytes)) => match env.byte_array_from_slice(&bytes) {
            Ok(arr) => arr.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        _ => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_antigravity_filemanager_core_NativeEngine_getApkInfo<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    apk_path: JString<'local>,
) -> jstring {
    let path: String = match env.get_string(&apk_path) {
        Ok(s) => s.into(),
        Err(_) => {
            let err = env.new_string("{\"error\":\"Invalid path\"}").unwrap();
            return err.into_raw();
        }
    };

    match apk_parser::parse_apk_manifest(&path) {
        Ok(Some(info)) => {
            let json = serde_json::to_string(&info).unwrap_or_else(|_| "{}".to_string());
            let out = env.new_string(json).unwrap_or_else(|_| env.new_string("{}").unwrap());
            out.into_raw()
        }
        Ok(None) => {
            let out = env.new_string("{}").unwrap();
            out.into_raw()
        }
        Err(e) => {
            let err_json = format!("{{\"error\":\"{}\"}}", e);
            let out = env.new_string(err_json).unwrap_or_else(|_| env.new_string("{}").unwrap());
            out.into_raw()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_antigravity_filemanager_core_NativeEngine_scanDocuments<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    root_paths_json: JString<'local>,
    exts_json: JString<'local>,
    max_depth: jint,
) -> jstring {
    let roots: Vec<String> = match env.get_string(&root_paths_json) {
        Ok(s) => serde_json::from_str(&s.to_string_lossy()).unwrap_or_default(),
        Err(_) => Vec::new(),
    };
    let exts: std::collections::HashSet<String> = match env.get_string(&exts_json) {
        Ok(s) => serde_json::from_str(&s.to_string_lossy()).unwrap_or_default(),
        Err(_) => std::collections::HashSet::new(),
    };

    let entries = doc_scanner::scan_documents(&roots, &exts, max_depth as usize);
    let json = serde_json::to_string(&entries).unwrap_or_else(|_| "[]".to_string());
    let out = env.new_string(json).unwrap_or_else(|_| env.new_string("[]").unwrap());
    out.into_raw()
}


#[no_mangle]
pub extern "system" fn Java_com_antigravity_filemanager_core_NativeEngine_extractEmbeddedThumbnail<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
) -> jbyteArray {
    let path_str: String = match env.get_string(&path) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    match image_meta::extract_embedded_thumbnail(&path_str) {
        Ok(Some(bytes)) => match env.byte_array_from_slice(&bytes) {
            Ok(arr) => arr.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        _ => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_antigravity_filemanager_core_NativeEngine_extractVideoPoster<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
) -> jbyteArray {
    let path_str: String = match env.get_string(&path) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    match video_meta::extract_video_poster(&path_str) {
        Ok(Some(bytes)) => match env.byte_array_from_slice(&bytes) {
            Ok(arr) => arr.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        _ => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_antigravity_filemanager_core_NativeEngine_extractAudioCover<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
) -> jbyteArray {
    let path_str: String = match env.get_string(&path) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    match audio_meta::extract_audio_cover(&path_str) {
        Ok(Some(bytes)) => match env.byte_array_from_slice(&bytes) {
            Ok(arr) => arr.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        _ => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_antigravity_filemanager_core_NativeEngine_getAudioMetadata<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
) -> jstring {
    let path_str: String = match env.get_string(&path) {
        Ok(s) => s.into(),
        Err(_) => {
            let err = env.new_string("{}").unwrap();
            return err.into_raw();
        }
    };

    match audio_meta::parse_audio_tags(&path_str) {
        Ok(meta) => {
            let json = serde_json::to_string(&meta).unwrap_or_else(|_| "{}".to_string());
            let out = env.new_string(json).unwrap_or_else(|_| env.new_string("{}").unwrap());
            out.into_raw()
        }
        Err(_) => {
            let out = env.new_string("{}").unwrap();
            out.into_raw()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_antigravity_filemanager_core_NativeEngine_getImageMetadata<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
) -> jstring {
    let path_str: String = match env.get_string(&path) {
        Ok(s) => s.into(),
        Err(_) => {
            let err = env.new_string("{}").unwrap();
            return err.into_raw();
        }
    };

    match image_meta::get_image_metadata(&path_str) {
        Ok(meta) => {
            let json = serde_json::to_string(&meta).unwrap_or_else(|_| "{}".to_string());
            let out = env.new_string(json).unwrap_or_else(|_| env.new_string("{}").unwrap());
            out.into_raw()
        }
        Err(_) => {
            let out = env.new_string("{}").unwrap();
            out.into_raw()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_antigravity_filemanager_core_NativeEngine_getVideoMetadata<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
) -> jstring {
    let path_str: String = match env.get_string(&path) {
        Ok(s) => s.into(),
        Err(_) => {
            let err = env.new_string("{}").unwrap();
            return err.into_raw();
        }
    };

    match video_meta::parse_video_metadata(&path_str) {
        Ok(meta) => {
            let json = serde_json::to_string(&meta).unwrap_or_else(|_| "{}".to_string());
            let out = env.new_string(json).unwrap_or_else(|_| env.new_string("{}").unwrap());
            out.into_raw()
        }
        Err(_) => {
            let out = env.new_string("{}").unwrap();
            out.into_raw()
        }
    }
}
