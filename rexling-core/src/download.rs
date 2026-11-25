//! Stream a file from the tapped terminal to a private staging file.
use crate::{
    control::{Control, RpcError},
    transport::Endpoint,
    upload::{BYTES, Phase, bytes_handshake},
};
use anyhow::{Context, Result, bail};
use futures_util::{SinkExt, StreamExt};
use serde_json::json;
use std::{
    fs::OpenOptions,
    io::{BufWriter, Write},
    path::Path,
    time::Duration,
};
use tokio::sync::oneshot;
use tokio_tungstenite::tungstenite::{Message, protocol::frame::coding::CloseCode};

pub struct Request<'a> {
    pub endpoint: Endpoint,
    pub session: &'a str,
    pub block: &'a str,
    pub source: &'a str,
    pub destination: &'a Path,
    pub reference: &'a str,
}

pub async fn download(
    request: Request<'_>,
    mut cancel: oneshot::Receiver<()>,
    progress: impl Fn(Phase, u64, u64),
) -> (Phase, String) {
    let mut connection = None;
    let operation = async {
        let control = connection.insert(Control::connect_download(request.endpoint.clone()).await?);
        control.attach(request.session).await?;
        let result = control
            .terminal(
                request.session,
                request.block,
                "download_begin",
                json!({"ref":request.reference,"path":request.source}),
            )
            .await;
        let ticket = match result {
            Ok(ticket) => ticket,
            Err(error)
                if error.downcast_ref::<RpcError>().is_some_and(|e| {
                    matches!(e.code.as_str(), "method_not_found" | "revision_too_low")
                }) =>
            {
                return Ok::<_, anyhow::Error>(Phase::Unsupported);
            }
            Err(error) => return Err(error),
        };
        // The advertised size is for progress only. A remote file can change while being read.
        let total = ticket["size"].as_u64().unwrap_or(0);
        let mut stream = request.endpoint.open("/api/data", BYTES).await?;
        bytes_handshake(&mut stream, &ticket, request.endpoint.timeout).await?;
        let mut file = BufWriter::new(
            OpenOptions::new()
                .write(true)
                .truncate(true)
                .open(request.destination)
                .context("Could not stage the download")?,
        );
        progress(Phase::Receiving, 0, total);
        let mut received = 0_u64;
        let mut eof = false;
        loop {
            let message = tokio::time::timeout(Duration::from_secs(30), stream.next())
                .await
                .context("Download stalled")?
                .context("Download connection ended before the file was complete")??;
            match message {
                Message::Binary(bytes) if !eof => {
                    if bytes.is_empty() {
                        eof = true;
                    } else {
                        file.write_all(&bytes)
                            .context("Could not stage the download")?;
                        received = received
                            .checked_add(bytes.len() as u64)
                            .context("Download size overflow")?;
                        progress(Phase::Receiving, received, total);
                    }
                }
                Message::Close(Some(close)) if eof && close.code == CloseCode::Normal => {
                    file.flush().context("Could not finish the download")?;
                    let _ = tokio::time::timeout(Duration::from_secs(2), stream.flush()).await;
                    progress(Phase::Done, received, received);
                    return Ok(Phase::Done);
                }
                Message::Close(_) => bail!("Rex closed the stream before the download finished"),
                Message::Ping(_) | Message::Pong(_) => stream.flush().await?,
                _ => bail!("Unexpected download response"),
            }
        }
    };
    let result: Option<Result<Phase>> = tokio::select! {
        biased;
        _ = &mut cancel => None,
        result = operation => Some(result),
    };
    if let Some(mut control) = connection {
        let _ = tokio::time::timeout(Duration::from_secs(2), control.close()).await;
    }
    match result {
        Some(Ok(phase)) => (phase, String::new()),
        Some(Err(error)) => (Phase::Failed, error.to_string()),
        None => (Phase::Canceled, String::new()),
    }
}
