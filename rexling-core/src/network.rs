//! Android's embedded Go transport exposes connected byte streams as owned FDs.
use anyhow::{Result, Context};
use std::{ffi::{CString,CStr,c_char,c_int,c_void}, sync::atomic::{AtomicBool,Ordering}, os::fd::FromRawFd};
static BUILT_IN: AtomicBool=AtomicBool::new(false);
unsafe extern "C" {
    fn rexnet_command(request: *const c_char) -> *mut c_char;
    fn rexnet_dial(endpoint: *const c_char, milliseconds: c_int, error: *mut *mut c_char) -> c_int;
    fn rexnet_probe_dial(endpoint: *const c_char, options: *const c_char, milliseconds: c_int, error: *mut *mut c_char) -> c_int;
    fn rexnet_free(pointer: *mut c_void);
}
pub fn enabled() -> bool {BUILT_IN.load(Ordering::Acquire)}
pub fn command(request: &str) -> Result<String> {
    let parsed: serde_json::Value=serde_json::from_str(request)?;
    let input=CString::new(request)?;
    let pointer=unsafe {rexnet_command(input.as_ptr())};
    anyhow::ensure!(!pointer.is_null(),"Native network response missing");
    let response=unsafe {CStr::from_ptr(pointer).to_string_lossy().into_owned()};
    unsafe {rexnet_free(pointer.cast())};
    let value: serde_json::Value=serde_json::from_str(&response)?;
    if parsed["type"]=="configure" && value["error"].is_null() {
        let config=&parsed["config"];
        let use_bridge=config["built_in"].as_bool().unwrap_or(false)
            || config["built_in_enabled"].as_bool().unwrap_or(false)
            || config["routes"].as_object().is_some_and(|routes|routes.values().any(|route|route.as_str()==Some("builtin")));
        BUILT_IN.store(use_bridge,Ordering::Release);
    }
    Ok(response)
}
pub async fn connect(endpoint: String, timeout: std::time::Duration, probe_options: Option<String>) -> Result<tokio::net::UnixStream> {
    let socket=tokio::task::spawn_blocking(move || -> Result<std::os::unix::net::UnixStream> {
        let endpoint=CString::new(endpoint)?;
        let mut error=std::ptr::null_mut();
        let timeout=timeout.as_millis().min(c_int::MAX as u128) as c_int;
        let descriptor=if let Some(options)=probe_options {
            let options=CString::new(options)?;
            unsafe {rexnet_probe_dial(endpoint.as_ptr(),options.as_ptr(),timeout,&mut error)}
        } else {
            unsafe {rexnet_dial(endpoint.as_ptr(),timeout,&mut error)}
        };
        if descriptor<0 {
            let message=if error.is_null() {"Connection failed".into()} else {
                let text=unsafe {CStr::from_ptr(error).to_string_lossy().into_owned()};
                unsafe {rexnet_free(error.cast())};text
            };
            anyhow::bail!("{message}");
        }
        let socket=unsafe {std::os::unix::net::UnixStream::from_raw_fd(descriptor)};
        socket.set_nonblocking(true)?;
        Ok(socket)
    }).await.context("Network worker stopped")??;
    Ok(tokio::net::UnixStream::from_std(socket)?)
}
