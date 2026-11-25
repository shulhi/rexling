//! Direct Rex transport and native Ghostty replica used by Rexling.
pub mod control;
pub mod data;
pub mod download;
pub mod ghostty;
pub mod snapshot;
pub mod transport;

pub mod upload;

pub mod graphics;

pub mod forward;

#[cfg(target_os = "android")]
pub mod network;
