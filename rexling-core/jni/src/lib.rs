mod activity;
mod progress;
mod engine;
mod updates;
mod uploads;
mod forwards;
mod mirror;
use engine::Session;
use jni::{
    JNIEnv,
    objects::{JClass, JString},
    sys::{jlong, jstring, jbyteArray},
};
use std::{
    collections::HashMap,
    sync::{
        Mutex, OnceLock,
        atomic::{AtomicU64, Ordering},
    },
};

static SESSIONS: OnceLock<Mutex<HashMap<u64, Session>>> = OnceLock::new();
static NEXT: AtomicU64 = AtomicU64::new(1);
fn sessions() -> &'static Mutex<HashMap<u64, Session>> {
    SESSIONS.get_or_init(|| Mutex::new(HashMap::new()))
}
fn string(env: &mut JNIEnv, value: String) -> jstring {
    env.new_string(value)
        .map(JString::into_raw)
        .unwrap_or(std::ptr::null_mut())
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_shulhi_rex_NativeBridge_create(
    mut env: JNIEnv,
    _class: JClass,
) -> jlong {
    let result = std::panic::catch_unwind(|| -> anyhow::Result<u64> {
        let session = Session::spawn()?;
        let id = NEXT.fetch_add(1, Ordering::Relaxed);
        sessions()
            .lock()
            .map_err(|_| anyhow::anyhow!("session registry unavailable"))?
            .insert(id, session);
        Ok(id)
    });
    match result {
        Ok(Ok(id)) => id as jlong,
        _ => {
            let _ = env.throw_new(
                "java/lang/IllegalStateException",
                "Could not start terminal worker",
            );
            0
        }
    }
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_shulhi_rex_NativeBridge_command(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    request: JString,
) -> jstring {
    let result =
        std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> anyhow::Result<()> {
            let request: String = env.get_string(&request)?.into();
            let value = serde_json::from_str(&request)?;
            let sessions = sessions()
                .lock()
                .map_err(|_| anyhow::anyhow!("session registry unavailable"))?;
            sessions
                .get(&(handle as u64))
                .ok_or_else(|| anyhow::anyhow!("terminal session is closed"))?
                .command(value)
        }));
    let message = match result {
        Ok(Ok(())) => String::new(),
        Ok(Err(e)) => e.to_string(),
        Err(_) => "Native command failed".into(),
    };
    string(&mut env, message)
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_shulhi_rex_NativeBridge_poll(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    after: jlong,
) -> jstring {
    let result = std::panic::catch_unwind(|| -> anyhow::Result<String> {
        let sessions = sessions()
            .lock()
            .map_err(|_| anyhow::anyhow!("session registry unavailable"))?;
        sessions
            .get(&(handle as u64))
            .ok_or_else(|| anyhow::anyhow!("terminal session is closed"))?
            .poll(after.max(0) as u64)
    });
    string(
        &mut env,
        match result {
            Ok(Ok(value)) => value,
            _ => String::new(),
        },
    )
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_shulhi_rex_NativeBridge_destroy(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    let _ = std::panic::catch_unwind(|| {
        if let Ok(mut sessions) = sessions().lock() {
            sessions.remove(&(handle as u64));
        }
    });
}

/// Wait without holding the handle registry lock. Destroy wakes pending readers.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_shulhi_rex_NativeBridge_awaitUpdate(
    _env: JNIEnv, _class: JClass, handle: jlong, after: jlong, timeout_ms: jlong,
) -> jlong {
    std::panic::catch_unwind(|| {
        let notifier=sessions().lock().ok().and_then(|s|s.get(&(handle as u64)).map(|s|s.updates.clone()));
        notifier.map_or(-1,|n|n.wait(after.max(0) as u64,timeout_ms.clamp(1,1000) as u64))
    }).unwrap_or(-1)
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_shulhi_rex_NativeBridge_readUpdate(
    mut env: JNIEnv, _class: JClass, handle: jlong, after: jlong,
) -> jbyteArray {
    let result=std::panic::catch_unwind(|| -> anyhow::Result<Vec<u8>> {
        let shared=sessions().lock().map_err(|_|anyhow::anyhow!("registry unavailable"))?
            .get(&(handle as u64)).map(|s|s.shared.clone()).ok_or_else(||anyhow::anyhow!("closed"))?;
        let mut state=shared.lock().map_err(|_|anyhow::anyhow!("state unavailable"))?;
        Ok(updates::encode(&mut state,after.max(0) as u64))
    });
    match result {Ok(Ok(bytes))=>env.byte_array_from_slice(&bytes).map(|a|a.into_raw()).unwrap_or(std::ptr::null_mut()),
        _=>{let _=env.throw_new("java/lang/IllegalStateException","Native update failed");std::ptr::null_mut()}}
}

mod input_queue;

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_shulhi_rex_NativeBridge_uploadCreate(mut env:JNIEnv,_class:JClass,request:JString)->jlong {
    create_file_transfer(&mut env, request, false)
}
fn create_file_transfer(env: &mut JNIEnv, request: JString, receiving: bool) -> jlong {
    let result=std::panic::catch_unwind(std::panic::AssertUnwindSafe(||->anyhow::Result<u64>{
        let request:String=env.get_string(&request)?.into();uploads::create(serde_json::from_str(&request)?,receiving)
    }));
    match result {
        Ok(Ok(id))=>id as jlong,
        _=>{let _=env.throw_new("java/lang/IllegalStateException",if receiving {"Could not start file download"} else {"Could not start file upload"});0}
    }
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_shulhi_rex_NativeBridge_uploadPoll(mut env:JNIEnv,_class:JClass,handle:jlong)->jstring {
    string(&mut env,uploads::poll(handle as u64).to_string())
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_shulhi_rex_NativeBridge_uploadDestroy(_env:JNIEnv,_class:JClass,handle:jlong) {uploads::destroy(handle as u64);}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_shulhi_rex_NativeBridge_downloadCreate(mut env:JNIEnv,_class:JClass,request:JString)->jlong {
    create_file_transfer(&mut env, request, true)
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_shulhi_rex_NativeBridge_downloadPoll(mut env:JNIEnv,_class:JClass,handle:jlong)->jstring {
    string(&mut env,uploads::poll(handle as u64).to_string())
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_shulhi_rex_NativeBridge_downloadDestroy(_env:JNIEnv,_class:JClass,handle:jlong) {uploads::destroy(handle as u64);}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_shulhi_rex_NativeBridge_forwardCreate(mut env: JNIEnv, _class: JClass, request: JString) -> jlong {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> anyhow::Result<u64> {
        let request: String = env.get_string(&request)?.into();
        forwards::create(serde_json::from_str(&request)?)
    }));
    match result {
        Ok(Ok(id)) => id as jlong,
        error => {
            let message = match error {Ok(Err(e))=>e.to_string(),_=>"Could not start forward".into()};
            let _=env.throw_new("java/lang/IllegalStateException",message);
            0
        }
    }
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_shulhi_rex_NativeBridge_forwardPoll(mut env: JNIEnv, _class: JClass, id: jlong) -> jstring {
    let value = std::panic::catch_unwind(||forwards::poll(id as u64)).unwrap_or_else(|_|serde_json::json!({"phase":"failed"}));
    string(&mut env,value.to_string())
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_shulhi_rex_NativeBridge_forwardDestroy(_env: JNIEnv, _class: JClass, id: jlong) {
    let _=std::panic::catch_unwind(||forwards::destroy(id as u64));
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_shulhi_rex_NativeBridge_networkCommand(mut env: JNIEnv, _class: JClass, request: JString) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> anyhow::Result<String> {
        let request: String = env.get_string(&request)?.into();
        #[cfg(target_os = "android")]
        return rex_core::network::command(&request);
        #[cfg(not(target_os = "android"))]
        { let _ = request; anyhow::bail!("Android networking bridge is unavailable on this platform") }
    }));
    let response = match result {Ok(Ok(v))=>v,Ok(Err(e))=>serde_json::json!({"error":e.to_string()}).to_string(),Err(_)=>"{\"error\":\"Network command failed\"}".into()};
    string(&mut env,response)
}
