//! Use a disposable Rex server and terminal to check its real byte-stream framing.
use rexling_core::{
    download::{self, Request},
    transport::Endpoint,
    upload::Phase,
};
use std::{env, path::Path, time::Duration};
use tokio::sync::oneshot;

#[tokio::test]
#[ignore = "requires a disposable Rex server and a fixture file"]
async fn downloads_file_from_a_rex_terminal() {
    let required = |key: &str| env::var(key).unwrap_or_else(|_| panic!("set {key}"));
    let endpoint =
        Endpoint::new(&required("REX_DOWNLOAD_SERVER"), Duration::from_secs(10)).unwrap();
    let session = required("REX_DOWNLOAD_SESSION");
    let block = required("REX_DOWNLOAD_BLOCK");
    let source = required("REX_DOWNLOAD_SOURCE");
    let destination = required("REX_DOWNLOAD_DESTINATION");
    let reference = required("REX_DOWNLOAD_REFERENCE");
    let (_cancel, cancel) = oneshot::channel();
    let (phase, error) = download::download(
        Request {
            endpoint,
            session: &session,
            block: &block,
            source: &source,
            destination: Path::new(&destination),
            reference: &reference,
        },
        cancel,
        |_, _, _| {},
    )
    .await;
    assert_eq!(phase, Phase::Done, "{error}");
    assert_eq!(
        std::fs::read(&destination).unwrap(),
        std::fs::read(&source).unwrap()
    );
}
