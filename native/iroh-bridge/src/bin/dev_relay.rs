//! The relay of the tests: an iroh relay on the loopback over plain HTTP (as `iroh-relay --dev`).
//! It prints its address, then runs until its standard input is closed.
//!
//! Usage: dev_relay [port] [token]   (port 0 or nothing: any free port; with a token, only the
//! clients giving it are admitted, as with `access.shared_token`)

use std::io::Read;
use std::net::Ipv4Addr;
use std::sync::Arc;

use iroh_relay::server::{Access, AccessControl, AllowAll, ClientRequest, RelayConfig, Server, ServerConfig};

/// Admits the clients giving [Token.0] only.
#[derive(Debug)]
struct Token(String);

impl AccessControl for Token {
    async fn on_connect(&self, request: &ClientRequest) -> Access {
        match request.auth_token() {
            Some(token) if token == self.0 => Access::Allow,
            _ => Access::Deny { reason: None },
        }
    }
}

#[tokio::main(flavor = "multi_thread", worker_threads = 2)]
async fn main() {
    let mut args = std::env::args().skip(1);
    let port: u16 = args.next().and_then(|port| port.parse().ok()).unwrap_or(0);
    let mut relay = RelayConfig::new((Ipv4Addr::LOCALHOST, port));
    relay.access = match args.next() {
        Some(token) => Arc::new(Token(token)),
        None => Arc::new(AllowAll),
    };
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
