pub mod analyzer;
pub mod image_engine;

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jint, jlong, jstring};

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
