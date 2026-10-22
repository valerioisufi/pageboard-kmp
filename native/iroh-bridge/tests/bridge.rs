//! The bridge between two endpoints of this process: directly, and through a relay on the
//! loopback.

use std::net::Ipv4Addr;
use std::sync::Arc;

use iroh_bridge::bridge::{self, Failure, MAX_FRAME};
use iroh_relay::server::{AllowAll, RelayConfig, Server, ServerConfig};

const ALPN: &str = "drawview/test";

fn key(seed: u8) -> Vec<u8> {
    (0..32).map(|i| seed.wrapping_add(i)).collect()
}

/// The next frame of [stream], of any size.
fn receive(stream: u64, timeout_ms: i64) -> bridge::Result<Vec<u8>> {
    bridge::receive(stream, timeout_ms, MAX_FRAME)
}

/// Two endpoints with a stream between them: (endpoints, connections, streams), the first of
/// each pair on the side that connected.
fn pair(relays: &str, address_of_second: impl Fn(&str) -> String) -> ([u64; 2], [u64; 2], [u64; 2]) {
    let first = bridge::bind(&key(1), relays, "", ALPN).unwrap();
    let second = bridge::bind(&key(2), relays, "", ALPN).unwrap();
    let address = address_of_second(&bridge::address(second, 10_000).unwrap());
    let opened = bridge::connect(first, &address, 15_000).unwrap();
    let accepted = bridge::accept(second, 15_000).unwrap();
    let out = bridge::open_stream(opened).unwrap();
    // The other side sees the stream with its first frame
    bridge::send(out, b"hello".to_vec(), 5_000).unwrap();
    let back = bridge::accept_stream(accepted, 5_000).unwrap();
    assert_eq!(receive(back, 5_000).unwrap(), b"hello");
    ([first, second], [opened, accepted], [out, back])
}

#[test]
fn frames_travel_both_ways_whole() {
    let (endpoints, connections, [out, back]) = pair("", |address| address.to_string());
    for i in 0..100usize {
        let frame = vec![(i % 251) as u8; i * 37];
        bridge::send(out, frame.clone(), 5_000).unwrap();
        assert_eq!(receive(back, 5_000).unwrap(), frame);
        bridge::send(back, frame.clone(), 5_000).unwrap();
        assert_eq!(receive(out, 5_000).unwrap(), frame);
    }
    let big: Vec<u8> = (0..1_500_000usize).map(|i| (i % 253) as u8).collect();
    bridge::send(out, big.clone(), 5_000).unwrap();
    assert_eq!(receive(back, 10_000).unwrap(), big);
    assert_eq!(bridge::path(connections[0]).unwrap().0, 1, "direct");
    assert_eq!(bridge::remote_id(connections[1]).unwrap(), bridge::id(endpoints[0]).unwrap());
    endpoints.iter().for_each(|&e| bridge::close_endpoint(e));
}

#[test]
fn a_receive_with_nothing_times_out_and_can_be_made_again() {
    let (endpoints, _, [out, back]) = pair("", |address| address.to_string());
    assert!(matches!(receive(back, 100), Err(Failure::Timeout)));
    assert!(!bridge::is_ended(back));
    bridge::send(out, b"later".to_vec(), 5_000).unwrap();
    assert_eq!(receive(back, 5_000).unwrap(), b"later");
    endpoints.iter().for_each(|&e| bridge::close_endpoint(e));
}

#[test]
fn a_stream_closed_gives_its_last_frames_then_its_end() {
    let (endpoints, _, [out, back]) = pair("", |address| address.to_string());
    for i in 0..5u8 {
        bridge::send(out, vec![i; 1000], 5_000).unwrap();
    }
    bridge::close_stream(out);
    for i in 0..5u8 {
        assert_eq!(receive(back, 5_000).unwrap(), vec![i; 1000]);
    }
    assert!(matches!(receive(back, 5_000), Err(Failure::Closed)));
    assert!(bridge::is_ended(back));
    // The number of a stream closed is refused
    assert!(matches!(bridge::send(out, vec![1], 100), Err(Failure::Closed)));
    endpoints.iter().for_each(|&e| bridge::close_endpoint(e));
}

#[test]
fn a_connection_closed_ends_its_streams_on_the_other_side() {
    let (endpoints, [opened, _], [_, back]) = pair("", |address| address.to_string());
    bridge::close_connection(opened);
    assert!(matches!(receive(back, 5_000), Err(Failure::Closed)));
    endpoints.iter().for_each(|&e| bridge::close_endpoint(e));
}

#[test]
fn an_endpoint_closed_refuses_its_calls() {
    let endpoint = bridge::bind(&key(9), "", "", ALPN).unwrap();
    bridge::close_endpoint(endpoint);
    assert!(matches!(bridge::accept(endpoint, 100), Err(Failure::Closed)));
    assert!(matches!(bridge::address(endpoint, 100), Err(Failure::Closed)));
    assert!(matches!(bridge::bind(&[0; 3], "", "", ALPN), Err(Failure::Error(_))));
    assert!(matches!(bridge::bind(&key(9), "not a url", "", ALPN), Err(Failure::Error(_))));
}

#[test]
fn two_endpoints_meet_through_their_relay_by_identifier_only() {
    let runtime = tokio::runtime::Runtime::new().unwrap();
    let server = runtime.block_on(async {
        let mut relay = RelayConfig::new((Ipv4Addr::LOCALHOST, 0));
        relay.access = Arc::new(AllowAll);
        let mut config = ServerConfig::default();
        config.relay = Some(relay);
        Server::spawn(config).await.unwrap()
    });
    let url = format!("http://{}", server.http_addr().unwrap());
    // What a device learns of another one: its identifier and its relay, no direct address
    let (endpoints, connections, [out, back]) = pair(&url, |address| {
        let parts: Vec<&str> = address.split_whitespace().collect();
        assert!(parts.iter().any(|part| part.starts_with("http://")), "{address}");
        parts.into_iter().filter(|part| !part.contains(':') || part.contains("://")).collect::<Vec<_>>().join(" ")
    });
    bridge::send(out, vec![7; 300_000], 5_000).unwrap();
    assert_eq!(receive(back, 10_000).unwrap(), vec![7; 300_000]);
    assert_ne!(bridge::path(connections[0]).unwrap().0, 0);
    endpoints.iter().for_each(|&e| bridge::close_endpoint(e));
    runtime.block_on(server.shutdown()).unwrap();
}

#[test]
fn a_frame_larger_than_allowed_is_held_back_unread() {
    let (endpoints, _, [out, back]) = pair("", |address| address.to_string());
    // Behind a small one, taken by a receive allowing it
    bridge::send(out, vec![1; 100], 5_000).unwrap();
    bridge::send(out, vec![2; 5_000], 5_000).unwrap();
    assert_eq!(bridge::receive(back, 5_000, 1_000).unwrap(), vec![1; 100]);
    assert_eq!(bridge::receive(back, 5_000, 5_000).unwrap(), vec![2; 5_000]);

    // Never allowed: not read, so the sender waits (the frame being written and the 8 queued),
    // then the stream fails
    let large = vec![3; 2_000_000];
    for _ in 0..9 {
        bridge::send(out, large.clone(), 5_000).unwrap();
    }
    assert!(matches!(bridge::send(out, large, 1_000), Err(Failure::Timeout)));
    let failed = (0..50).find_map(|_| match bridge::receive(back, 100, 1_000) {
        Err(Failure::Timeout) => None,
        other => Some(other),
    });
    assert!(matches!(failed, Some(Err(Failure::Error(_)))), "{failed:?}");
    assert!(bridge::is_ended(back));
    endpoints.iter().for_each(|&e| bridge::close_endpoint(e));
}

#[test]
fn an_endpoint_keeps_a_few_connections_of_others_at_once() {
    let server = bridge::bind(&key(50), "", "", ALPN).unwrap();
    let address = bridge::address(server, 10_000).unwrap();
    let clients: Vec<u64> = (0..33).map(|i| bridge::bind(&key(60 + i), "", "", ALPN).unwrap()).collect();
    for &client in &clients {
        // The last one is closed as soon as it is open
        let _ = bridge::connect(client, &address, 15_000);
    }
    let accepted: Vec<u64> = (0..32).map(|_| bridge::accept(server, 5_000).unwrap()).collect();
    assert!(matches!(bridge::accept(server, 500), Err(Failure::Timeout)));

    // One closed: room for another
    bridge::close_connection(accepted[0]);
    bridge::connect(clients[32], &address, 15_000).unwrap();
    let again = bridge::accept(server, 5_000).unwrap();
    assert_eq!(bridge::remote_id(again).unwrap(), bridge::id(clients[32]).unwrap());
    clients.iter().chain([&server]).for_each(|&e| bridge::close_endpoint(e));
}
