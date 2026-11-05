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
pub mod mega_crypto;
pub mod cache_codec;
pub mod cloud_tree;

use jni::JNIEnv;
use jni::objects::{JClass, JString, JByteArray};
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

#[no_mangle]
pub extern "system" fn Java_com_antigravity_filemanager_core_NativeEngine_scanFolderBreakdown<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    dir_path: JString<'local>,
) -> jstring {
    let path_str: String = match env.get_string(&dir_path) {
        Ok(s) => s.into(),
        Err(_) => {
            let err = env.new_string("[]").unwrap();
            return err.into_raw();
        }
    };

    let items = scanner::fast_folder_breakdown(&path_str);
    let json = serde_json::to_string(&items).unwrap_or_else(|_| "[]".to_string());
    let out = env.new_string(json).unwrap_or_else(|_| env.new_string("[]").unwrap());
    out.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_antigravity_filemanager_core_NativeEngine_calculateBlockMd5s<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
    block_size: jint,
) -> jstring {
    let path_str: String = match env.get_string(&path) {
        Ok(s) => s.into(),
        Err(_) => return env.new_string("[]").unwrap().into_raw(),
    };
    let bsize = if block_size > 0 { block_size as usize } else { 4 * 1024 * 1024 };
    match hasher::compute_block_md5s(&path_str, bsize) {
        Ok(list) => {
            let json = serde_json::to_string(&list).unwrap_or_else(|_| "[]".to_string());
            env.new_string(json).unwrap_or_else(|_| env.new_string("[]").unwrap()).into_raw()
        }
        Err(_) => env.new_string("[]").unwrap().into_raw(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_antigravity_filemanager_core_NativeEngine_calculateDropboxContentHash<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
) -> jstring {
    let path_str: String = match env.get_string(&path) {
        Ok(s) => s.into(),
        Err(_) => return env.new_string("").unwrap().into_raw(),
    };
    match hasher::compute_dropbox_content_hash(&path_str) {
        Ok(hash) => env.new_string(hash).unwrap_or_else(|_| env.new_string("").unwrap()).into_raw(),
        Err(_) => env.new_string("").unwrap().into_raw(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_antigravity_filemanager_core_NativeEngine_computeMegaChunkedMac<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
    aes_key: JByteArray<'local>,
    nonce: JByteArray<'local>,
) -> jbyteArray {
    let path_str: String = match env.get_string(&path) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let key_vec = match env.convert_byte_array(&aes_key) {
        Ok(v) if v.len() >= 16 => v,
        _ => return std::ptr::null_mut(),
    };
    let nonce_vec = match env.convert_byte_array(&nonce) {
        Ok(v) if v.len() >= 8 => v,
        _ => return std::ptr::null_mut(),
    };

    let mut file = match std::fs::File::open(&path_str) {
        Ok(f) => f,
        Err(_) => return std::ptr::null_mut(),
    };

    let mut mac = mega_crypto::MegaChunkedMac::new(&key_vec, &nonce_vec);
    let mut buffer = [0u8; 64 * 1024];
    use std::io::Read;
    loop {
        match file.read(&mut buffer) {
            Ok(0) => break,
            Ok(n) => mac.update(&buffer[..n]),
            Err(_) => return std::ptr::null_mut(),
        }
    }
    let out = mac.condense();
    match env.byte_array_from_slice(&out) {
        Ok(arr) => arr.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_antigravity_filemanager_core_NativeEngine_batchDecryptMegaNodes<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    nodes_json: JString<'local>,
    master_key: JByteArray<'local>,
) -> jstring {
    let json_str: String = match env.get_string(&nodes_json) {
        Ok(s) => s.into(),
        Err(_) => return env.new_string("[]").unwrap().into_raw(),
    };
    let mk_vec = match env.convert_byte_array(&master_key) {
        Ok(v) if v.len() >= 16 => v,
        _ => return env.new_string("[]").unwrap().into_raw(),
    };

    let nodes: Vec<mega_crypto::RawMegaNodeInput> = match serde_json::from_str(&json_str) {
        Ok(n) => n,
        Err(_) => return env.new_string("[]").unwrap().into_raw(),
    };

    let decrypted = mega_crypto::batch_decrypt_mega_nodes(&nodes, &mk_vec);
    let out_json = serde_json::to_string(&decrypted).unwrap_or_else(|_| "[]".to_string());
    env.new_string(out_json).unwrap_or_else(|_| env.new_string("[]").unwrap()).into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_antigravity_filemanager_core_NativeEngine_decryptMegaCtrSlice<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    data: JByteArray<'local>,
    aes_key: JByteArray<'local>,
    nonce: JByteArray<'local>,
    start_offset: jlong,
) -> jboolean {
    let key_vec = match env.convert_byte_array(&aes_key) {
        Ok(v) if v.len() >= 16 => v,
        _ => return 0,
    };
    let nonce_vec = match env.convert_byte_array(&nonce) {
        Ok(v) if v.len() >= 8 => v,
        _ => return 0,
    };

    let mut data_vec = match env.convert_byte_array(&data) {
        Ok(v) => v,
        Err(_) => return 0,
    };

    mega_crypto::mega_ctr_decrypt_slice(&mut data_vec, &key_vec, &nonce_vec, start_offset as u64);
    let i8_slice: Vec<i8> = data_vec.into_iter().map(|b| b as i8).collect();
    if env.set_byte_array_region(&data, 0, &i8_slice).is_ok() {
        1
    } else {
        0
    }
}

#[no_mangle]
pub extern "system" fn Java_com_antigravity_filemanager_core_NativeEngine_saveFolderCacheNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    file_path: JString<'local>,
    timestamp: jlong,
    items_json: JString<'local>,
) -> jboolean {
    let path: String = match env.get_string(&file_path) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    let json_str: String = match env.get_string(&items_json) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };

    let items: Vec<cache_codec::CacheFolderEntry> = match serde_json::from_str(&json_str) {
        Ok(it) => it,
        Err(_) => return 0,
    };

    match cache_codec::save_cache_to_file(&path, timestamp as i64, items) {
        Ok(_) => 1,
        Err(_) => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_antigravity_filemanager_core_NativeEngine_loadFolderCacheNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    file_path: JString<'local>,
) -> jstring {
    let path: String = match env.get_string(&file_path) {
        Ok(s) => s.into(),
        Err(_) => return env.new_string("").unwrap().into_raw(),
    };

    match cache_codec::load_cache_from_file(&path) {
        Ok(payload) => {
            let out_json = serde_json::to_string(&payload).unwrap_or_default();
            env.new_string(out_json).unwrap_or_else(|_| env.new_string("").unwrap()).into_raw()
        }
        Err(_) => env.new_string("").unwrap().into_raw(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_antigravity_filemanager_core_NativeEngine_storeMegaCloudTree<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    account_id: JString<'local>,
    nodes_json: JString<'local>,
    master_key: JByteArray<'local>,
) -> jstring {
    let acc_id: String = match env.get_string(&account_id) {
        Ok(s) => s.into(),
        Err(_) => return env.new_string("").unwrap().into_raw(),
    };
    let json_str: String = match env.get_string(&nodes_json) {
        Ok(s) => s.into(),
        Err(_) => return env.new_string("").unwrap().into_raw(),
    };
    let mk_vec = match env.convert_byte_array(&master_key) {
        Ok(v) if v.len() >= 16 => v,
        _ => return env.new_string("").unwrap().into_raw(),
    };

    let raw_nodes: Vec<mega_crypto::RawMegaNodeInput> = match serde_json::from_str(&json_str) {
        Ok(n) => n,
        Err(_) => return env.new_string("").unwrap().into_raw(),
    };

    let decrypted = mega_crypto::batch_decrypt_mega_nodes(&raw_nodes, &mk_vec);
    let mut root_handle = String::new();
    let cloud_nodes: Vec<cloud_tree::CloudNode> = decrypted.into_iter().map(|n| {
        if n.node_type == 2 {
            root_handle = n.handle.clone();
        }
        cloud_tree::CloudNode {
            id: n.handle,
            parent_id: n.parent_handle,
            name: n.name,
            is_dir: n.node_type == 1 || n.node_type == 2,
            size: n.size,
            last_modified: n.timestamp,
            extra: format!("{}:{}:{}", n.node_type, n.key_str, n.file_attr_str),
        }
    }).collect();

    let count = cloud_nodes.len();
    cloud_tree::store_tree(&acc_id, cloud_nodes, &root_handle);

    let summary = serde_json::json!({
        "count": count,
        "root_handle": root_handle
    });
    env.new_string(summary.to_string()).unwrap_or_else(|_| env.new_string("").unwrap()).into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_antigravity_filemanager_core_NativeEngine_getCloudTreeChildren<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    account_id: JString<'local>,
    parent_id: JString<'local>,
    sort_option: jint,
) -> jstring {
    let acc_id: String = match env.get_string(&account_id) {
        Ok(s) => s.into(),
        Err(_) => return env.new_string("[]").unwrap().into_raw(),
    };
    let parent: String = match env.get_string(&parent_id) {
        Ok(s) => s.into(),
        Err(_) => return env.new_string("[]").unwrap().into_raw(),
    };

    let children = cloud_tree::get_children(&acc_id, &parent, sort_option as i32);
    let json_str = serde_json::to_string(&children).unwrap_or_else(|_| "[]".to_string());
    env.new_string(json_str).unwrap_or_else(|_| env.new_string("[]").unwrap()).into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_antigravity_filemanager_core_NativeEngine_getCloudTreeChildCounts<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    account_id: JString<'local>,
    parent_id: JString<'local>,
) -> jlong {
    let acc_id: String = match env.get_string(&account_id) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    let parent: String = match env.get_string(&parent_id) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };

    let (_total, sub, files) = cloud_tree::get_child_counts(&acc_id, &parent);
    ((sub as i64) << 32) | (files as i64 & 0xFFFFFFFF)
}

#[no_mangle]
pub extern "system" fn Java_com_antigravity_filemanager_core_NativeEngine_searchCloudTreeNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    account_id: JString<'local>,
    query: JString<'local>,
    max_results: jint,
) -> jstring {
    let acc_id: String = match env.get_string(&account_id) {
        Ok(s) => s.into(),
        Err(_) => return env.new_string("[]").unwrap().into_raw(),
    };
    let q: String = match env.get_string(&query) {
        Ok(s) => s.into(),
        Err(_) => return env.new_string("[]").unwrap().into_raw(),
    };

    let matches = cloud_tree::search_tree(&acc_id, &q, max_results.max(1) as usize);
    let json_str = serde_json::to_string(&matches).unwrap_or_else(|_| "[]".to_string());
    env.new_string(json_str).unwrap_or_else(|_| env.new_string("[]").unwrap()).into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_antigravity_filemanager_core_NativeEngine_invalidateCloudTree<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    account_id: JString<'local>,
) -> jboolean {
    let acc_id: String = match env.get_string(&account_id) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    cloud_tree::invalidate_tree(&acc_id);
    1
}

#[no_mangle]
pub extern "system" fn Java_com_antigravity_filemanager_core_NativeEngine_saveCloudTreeToFile<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    account_id: JString<'local>,
    file_path: JString<'local>,
) -> jboolean {
    let acc_id: String = match env.get_string(&account_id) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    let path: String = match env.get_string(&file_path) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };

    match cloud_tree::save_tree_to_file(&acc_id, &path) {
        Ok(_) => 1,
        Err(_) => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_antigravity_filemanager_core_NativeEngine_loadCloudTreeFromFile<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    account_id: JString<'local>,
    file_path: JString<'local>,
) -> jint {
    let acc_id: String = match env.get_string(&account_id) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };
    let path: String = match env.get_string(&file_path) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };

    match cloud_tree::load_tree_from_file(&acc_id, &path) {
        Ok(count) => count as jint,
        Err(_) => -1,
    }
}
