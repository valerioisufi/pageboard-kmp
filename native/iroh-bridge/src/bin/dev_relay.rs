//! The relay of the tests: an iroh relay on the loopback over plain HTTP (as `iroh-relay --dev`).
//! It prints its address, then runs until its standard input is closed.
//!
//! Usage: dev_relay [port]   (0 or nothing: any free port)

use std::io::Read;
use std::net::Ipv4Addr;
use std::sync::Arc;

use iroh_relay::server::{AllowAll, RelayConfig, Server, ServerConfig};

#[tokio::main(flavor = "multi_thread", worker_threads = 2)]
async fn main() {
    let port: u16 = std::env::args().nth(1).and_then(|port| port.parse().ok()).unwrap_or(0);
    let mut relay = RelayConfig::new((Ipv4Addr::LOCALHOST, port));
    relay.access = Arc::new(AllowAll);
    let mut config = ServerConfig::default();
    config.relay = Some(relay);
    let server = Server::spawn(config).await.expect("relay");
    println!("http://{}", server.http_addr().expect("http address"));
    // Until the process that started it closes the input (or ends)
    tokio::task::spawn_blocking(|| {
        let _ = std::io::stdin().read_to_end(&mut Vec::new());
    })
    .await
    .ok();
    server.shutdown().await.ok();
}
