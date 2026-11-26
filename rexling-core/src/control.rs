use crate::transport::{self, CONTROL, Endpoint, MAX_MESSAGE, Ws};
use anyhow::{Context, Result, bail, ensure};
use futures_util::SinkExt;
use serde_json::{Value, json};
use std::{
    collections::{BTreeMap, VecDeque},
    fs::{File, OpenOptions},
    io::Write,
    path::Path,
    sync::{Arc, Mutex},
};
use tokio::sync::oneshot;
use tokio_tungstenite::tungstenite::Message;

pub const UNDO_CLOSE_API_REVISION: u64 = 23;

pub fn redact(value: &mut Value) {
    match value {
        Value::Object(map) => {
            for (k, v) in map {
                if matches!(k.as_str(), "reconnect_token" | "ticket") {
                    *v = json!("[REDACTED]");
                } else {
                    redact(v);
                }
            }
        }
        Value::Array(items) => {
            for v in items {
                redact(v);
            }
        }
        _ => (),
    }
}

#[derive(Clone, Default)]
pub struct Trace(Option<Arc<Mutex<File>>>);
impl Trace {
    pub fn create(path: &Path) -> Result<Self> {
        let mut options = OpenOptions::new();
        options.write(true).create_new(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            options.mode(0o600);
        }
        Ok(Self(Some(Arc::new(Mutex::new(options.open(path)?)))))
    }
    pub fn record(&self, direction: &str, mut value: Value) -> Result<()> {
        redact(&mut value);
        if let Some(file) = &self.0 {
            let mut file = file
                .lock()
                .map_err(|_| anyhow::anyhow!("trace lock poisoned"))?;
            serde_json::to_writer(&mut *file, &json!({"direction":direction,"message":value}))?;
            writeln!(file)?;
        }
        Ok(())
    }
}

/// Preserve server error codes so callers can distinguish a stale resource from a failed operation.
#[derive(Debug)]
pub struct RpcError {
    pub method: String,
    pub code: String,
    pub message: String,
    pub status: Value,
}
impl std::fmt::Display for RpcError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(
            f,
            "{}: {} (code={}, status={})",
            self.method, self.message, self.code, self.status
        )
    }
}
impl std::error::Error for RpcError {}

/// A request already sent on the control stream. Reading the stream dispatches
/// its reply; awaiting this alone deliberately does not read from the socket.
pub struct PendingCall {
    id: String,
    method: String,
    deadline: tokio::time::Instant,
    receiver: oneshot::Receiver<Result<Value>>,
}
impl PendingCall {
    pub async fn receive(self) -> Result<Value> {
        tokio::time::timeout_at(self.deadline, self.receiver)
            .await
            .with_context(|| {
                format!(
                    "{} timed out; its outcome is unknown, inspect state before retrying",
                    self.method
                )
            })?
            .context("control connection closed; request outcome is unknown")?
    }
}
struct WaitingCall {
    method: String,
    sender: oneshot::Sender<Result<Value>>,
}

pub struct Control {
    ws: Ws,
    endpoint: Endpoint,
    welcome: Value,
    next_id: u64,
    pending: BTreeMap<String, WaitingCall>,
    pub events: VecDeque<Value>,
    last_seq: u64,
    trace: Trace,
}
impl Control {
    pub async fn connect(endpoint: Endpoint, token: Option<&str>, trace: Trace) -> Result<Self> {
        Self::connect_revision(endpoint, token, trace, 9).await
    }
    pub async fn connect_workspace(endpoint: Endpoint, token: Option<&str>) -> Result<Self> {
        Self::connect_revision(endpoint, token, Trace::default(), UNDO_CLOSE_API_REVISION).await
    }
    /// Uploads use their own connection so revision 11 never changes the live terminal contract.
    pub async fn connect_upload(endpoint: Endpoint) -> Result<Self> {
        Self::connect_revision(endpoint, None, Trace::default(), 11).await
    }
    pub async fn connect_download(endpoint: Endpoint) -> Result<Self> {
        Self::connect_revision(endpoint, None, Trace::default(), 19).await
    }
    pub async fn connect_forward(endpoint: Endpoint, token: Option<&str>) -> Result<Self> {
        Self::connect_revision(endpoint, token, Trace::default(), 14).await
    }
    async fn connect_revision(endpoint: Endpoint, token: Option<&str>, trace: Trace, revision: u64) -> Result<Self> {
        let mut ws = endpoint.open("/api/control", CONTROL).await?;
        let platform = match std::env::consts::OS {
            "macos" => "macos",
            "linux" => "linux",
            _ => "other",
        };
        // Older servers can negotiate down to revision 7.
        let mut hello = json!({"type":"hello","api_revision":revision,"api_min_revision":7,"client":{"kind":"app","name":"rexling","platform":platform,"version":env!("CARGO_PKG_VERSION")}});
        if let Some(token) = token {
            hello["reconnect_token"] = json!(token);
        }
        let encoded = serde_json::to_string(&hello)?;
        ensure!(encoded.len() < 512, "hello must be under 512 bytes");
        trace.record("control.send", hello)?;
        let welcome = tokio::time::timeout(endpoint.timeout, async {
            ws.send(Message::Text(encoded.into())).await?;
            let frame = transport::receive(&mut ws).await?;
            match frame {
                Message::Text(s) => Ok::<Value, anyhow::Error>(serde_json::from_str(&s)?),
                _ => bail!("expected text welcome"),
            }
        })
        .await
        .context("control hello timeout")??;
        trace.record("control.recv", welcome.clone())?;
        ensure!(
            welcome["type"] == "welcome" && welcome["protocol"] == CONTROL,
            "invalid control welcome"
        );
        ensure!(
            welcome["api_revision"].as_u64().is_some_and(|n| (7..=revision).contains(&n)),
            "unsupported negotiated API revision"
        );
        ensure!(
            welcome["limits"]["max_message_bytes"]
                .as_u64()
                .is_some_and(|n| n >= 65536),
            "invalid message limit"
        );
        Ok(Self {
            ws,
            endpoint,
            welcome,
            next_id: 1,
            pending: BTreeMap::new(),
            events: VecDeque::new(),
            last_seq: 0,
            trace,
        })
    }
    pub fn welcome(&self) -> Value {
        let mut v = self.welcome.clone();
        redact(&mut v);
        v
    }
    pub fn token(&self) -> Option<&str> {
        self.welcome["client"]["reconnect_token"].as_str()
    }
    pub fn client_id(&self) -> Option<&str> {
        self.welcome["client"]["id"].as_str()
    }
    pub fn last_seq(&self) -> u64 {
        self.last_seq
    }
    pub fn drain_events(&mut self) {
        self.events.clear();
    }

    pub async fn next_message(&mut self) -> Result<Value> {
        loop {
            let frame = transport::receive(&mut self.ws).await?;
            let Message::Text(text) = frame else {
                bail!("binary message on control connection");
            };
            let message: Value = serde_json::from_str(&text)?;
            self.trace.record("control.recv", message.clone())?;
            if message["type"] == "event" {
                let seq = message["seq"].as_u64().context("event has no seq")?;
                ensure!(
                    seq == self.last_seq + 1,
                    "event sequence gap: expected {}, got {seq}; reload state",
                    self.last_seq + 1
                );
                self.last_seq = seq;
            }
            if message["type"] == "response" {
                let id = message["id"]
                    .as_str()
                    .context("response has no request ID")?;
                let waiting = self.pending.remove(id).context("unexpected response ID")?;
                let result = if let Some(error) = message.get("error").filter(|e| !e.is_null()) {
                    Err(RpcError {
                        method: waiting.method,
                        code: error["code"].as_str().unwrap_or("").into(),
                        message: error["message"]
                            .as_str()
                            .unwrap_or("Server request failed")
                            .into(),
                        status: error["status"].clone(),
                    }
                    .into())
                } else {
                    Ok(message.get("payload").cloned().unwrap_or(Value::Null))
                };
                // Dropping a waiter does not make a late wire reply invalid.
                let _ = waiting.sender.send(result);
            }
            if matches!(message["type"].as_str(), Some("event" | "response")) {
                return Ok(message);
            }
        }
    }
    /// Send without waiting for the reply. The owner must continue calling
    /// next_message (or call) while awaiting the returned ticket.
    pub async fn start_call(&mut self, method: &str, payload: Value) -> Result<PendingCall> {
        ensure!(
            self.pending.len() < 32,
            "too many outstanding control requests"
        );
        let id = self.next_id.to_string();
        self.next_id += 1;
        let request = json!({"type":"request","id":id,"method":method,"payload":payload});
        let text = serde_json::to_string(&request)?;
        let max = self.welcome["limits"]["max_message_bytes"]
            .as_u64()
            .unwrap() as usize;
        ensure!(
            text.len() <= max.min(MAX_MESSAGE),
            "request exceeds message limit"
        );
        self.trace.record("control.send", request)?;
        let deadline = tokio::time::Instant::now() + self.endpoint.timeout;
        let (sender, receiver) = oneshot::channel();
        self.pending.insert(
            id.clone(),
            WaitingCall {
                method: method.into(),
                sender,
            },
        );
        if let Err(error) =
            tokio::time::timeout_at(deadline, self.ws.send(Message::Text(text.into())))
                .await
                .context("WebSocket request send timed out; outcome is unknown")
                .and_then(|result| {
                    result.context("WebSocket request send failed; outcome is unknown")
                })
        {
            self.pending.remove(&id);
            return Err(error);
        }
        Ok(PendingCall {
            id,
            method: method.into(),
            deadline,
            receiver,
        })
    }
    pub async fn call(&mut self, method: &str, payload: Value) -> Result<Value> {
        let pending = self.start_call(method, payload).await?;
        let id = pending.id.clone();
        let reply = pending.receive();
        tokio::pin!(reply);
        let result = loop {
            tokio::select! {
                biased;
                result=&mut reply=>break result,
                message=self.next_message()=>match message {
                    Ok(message) if message["type"]=="event"=>{
                        if self.events.len()>=4096 {break Err(anyhow::anyhow!("too many queued events; reload state"));}
                        self.events.push_back(message);
                    },
                    Ok(_)=>{},
                    Err(error)=>break Err(error),
                }
            }
        };
        self.pending.remove(&id);
        result
    }
    pub async fn attach(&mut self, session: &str) -> Result<Value> {
        self.call("session.attach", json!({"session_id":session}))
            .await
    }
    pub async fn terminal(
        &mut self,
        session: &str,
        block: &str,
        method: &str,
        args: Value,
    ) -> Result<Value> {
        self.call(
            &format!("com.superlogical.terminal.{method}"),
            json!({"session_id":session,"block_id":block,"args":args}),
        )
        .await
    }
    pub async fn ping(&mut self) -> Result<()> {
        self.ws.send(Message::Ping(Vec::new().into())).await?;
        Ok(())
    }
    pub async fn close(&mut self) -> Result<()> {
        self.pending.clear();
        self.ws.close().await?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn secrets_are_removed_recursively() {
        let mut v = json!({"client":{"reconnect_token":"s"},"items":[{"ticket":"s"}],"seq":4});
        redact(&mut v);
        assert_eq!(v["client"]["reconnect_token"], "[REDACTED]");
        assert_eq!(v["items"][0]["ticket"], "[REDACTED]");
        assert_eq!(v["seq"], 4);
    }
}
