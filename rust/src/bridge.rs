//! JNI surface for `com.lxl66566.echo.Native`.
//!
//! Contract: every function that can fail returns a JSON string
//! `{"ok":false,"error":".."}`; `push` returns null on success.
//! The engine pointer is a `Box<Mutex<Engine>>` cast to a jlong.

#[cfg(target_os = "android")]
use std::io::BufWriter;
use std::{ptr, slice, sync::Mutex};

#[cfg(target_os = "android")]
use jni::sys::jdouble;
use jni::{
    JNIEnv,
    objects::{JByteBuffer, JClass, JString},
    sys::{jint, jlong, jstring},
};
use serde_json::json;

#[cfg(target_os = "android")]
use crate::engine::SaveResult;
use crate::{
    config::EngineConfig,
    engine::{Engine, EngineError},
};

type SharedEngine = Mutex<Engine>;

fn to_jstring(env: &mut JNIEnv, s: &str) -> jstring {
    match env.new_string(s) {
        Ok(js) => js.as_raw(),
        // JVM string allocation failure; Kotlin side treats null as a fatal error.
        Err(_) => ptr::null_mut(),
    }
}

fn ok_json(env: &mut JNIEnv, value: &serde_json::Value) -> jstring {
    to_jstring(env, &value.to_string())
}

fn handle_ptr(handle: jlong) -> Result<&'static SharedEngine, EngineError> {
    (handle != 0)
        .then(|| unsafe { &*(handle as *const SharedEngine) })
        .ok_or_else(|| EngineError::Other("invalid handle".into()))
}

fn lock_poisoned() -> EngineError {
    EngineError::Other("engine mutex poisoned".into())
}

/// # Safety
/// `config` must be a valid local reference to a `java.lang.String`.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_lxl66566_echo_Native_create<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    config: JString<'local>,
) -> jstring {
    let result: Result<jlong, String> = (|| {
        let cfg_str = env
            .get_string(&config)
            .map_err(|e| format!("get_string: {e}"))?
            .to_str()
            .map_err(|e| format!("invalid utf-8: {e}"))?
            .to_owned();
        let engine = Engine::new(EngineConfig::parse(&cfg_str).map_err(|e| e.to_string())?)
            .map_err(|e: EngineError| e.to_string())?;
        Ok(Box::into_raw(Box::new(Mutex::new(engine))) as jlong)
    })();
    match result {
        Ok(handle) => ok_json(&mut env, &json!({"ok": true, "handle": handle})),
        Err(e) => ok_json(&mut env, &json!({"ok": false, "error": e})),
    }
}

/// # Safety
/// `handle` must come from `create` and not have been destroyed yet.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_lxl66566_echo_Native_destroy(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle != 0 {
        drop(unsafe { Box::from_raw(handle as *mut SharedEngine) });
    }
}

/// # Safety
/// `handle` must come from `create`; `buffer` must be a direct `ByteBuffer`
/// with at least `byte_count` accessible bytes.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_lxl66566_echo_Native_push<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    buffer: JByteBuffer<'local>,
    byte_count: jint,
) -> jstring {
    let result: Result<(), EngineError> = (|| {
        if byte_count < 0 {
            return Err(EngineError::Other("negative byte count".into()));
        }
        let engine = handle_ptr(handle)?;
        let addr = env
            .get_direct_buffer_address(&buffer)
            .map_err(|e| EngineError::Other(format!("not a direct buffer: {e}")))?;
        if addr.is_null() {
            return Err(EngineError::Other("direct buffer address is null".into()));
        }
        let data = unsafe { slice::from_raw_parts(addr, byte_count as usize) };
        let mut guard = engine.lock().map_err(|_| lock_poisoned())?;
        guard.push_pcm(data)
    })();
    match result {
        Ok(()) => ptr::null_mut(),
        Err(e) => ok_json(&mut env, &json!({"ok": false, "error": e.to_string()})),
    }
}

/// # Safety
/// `handle` must come from `create`; `fd` must be an open, writable file descriptor.
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_lxl66566_echo_Native_save<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    seconds: jdouble,
    fd: jint,
) -> jstring {
    use std::{
        fs::File,
        io::Write as _,
        mem::ManuallyDrop,
        os::fd::{FromRawFd, RawFd},
    };
    let result: Result<SaveResult, EngineError> = (|| {
        let engine = handle_ptr(handle)?;
        let mut guard = engine.lock().map_err(|_| lock_poisoned())?;
        // The fd is owned by Kotlin's ParcelFileDescriptor, which closes it.
        let file = ManuallyDrop::new(unsafe { File::from_raw_fd(fd as RawFd) });
        let mut writer = BufWriter::with_capacity(64 * 1024, &*file);
        let res = guard.save(seconds, &mut writer)?;
        writer.flush()?;
        Ok(res)
    })();
    match result {
        Ok(r) => ok_json(
            &mut env,
            &json!({
                "ok": true,
                "seconds_saved": r.seconds_saved,
                "bytes_written": r.bytes_written,
                "frames": r.frames,
            }),
        ),
        Err(e) => ok_json(&mut env, &json!({"ok": false, "error": e.to_string()})),
    }
}

/// # Safety
/// `handle` must come from `create`.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_lxl66566_echo_Native_stats<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jstring {
    let result: Result<String, EngineError> = (|| {
        let engine = handle_ptr(handle)?;
        let guard = engine.lock().map_err(|_| lock_poisoned())?;
        serde_json::to_string(&guard.stats()).map_err(|e| EngineError::Other(e.to_string()))
    })();
    match result {
        Ok(s) => to_jstring(&mut env, &s),
        Err(e) => ok_json(&mut env, &json!({"ok": false, "error": e.to_string()})),
    }
}
