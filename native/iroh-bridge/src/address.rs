//! The address of an endpoint as text: its identifier, then its relays and its direct addresses,
//! separated by spaces (`<id> https://relay.example/ 192.0.2.1:4433`).

use std::net::SocketAddr;
use std::str::FromStr;

use iroh::{EndpointAddr, EndpointId, RelayUrl, TransportAddr};

/// [addr] as text.
pub fn format(addr: &EndpointAddr) -> String {
    let mut text = addr.id.to_string();
    for url in addr.relay_urls() {
        text.push(' ');
        text.push_str(url.as_str());
    }
    for ip in addr.ip_addrs() {
        text.push(' ');
        text.push_str(&ip.to_string());
    }
    text
}

/// The address of [text], written by [format]; the parts it does not know are skipped.
pub fn parse(text: &str) -> Option<EndpointAddr> {
    let mut parts = text.split_whitespace();
    let id = EndpointId::from_str(parts.next()?).ok()?;
    let addrs = parts.filter_map(|part| {
        if part.contains("://") {
            RelayUrl::from_str(part).ok().map(TransportAddr::Relay)
        } else {
            SocketAddr::from_str(part).ok().map(TransportAddr::Ip)
        }
    });
    Some(EndpointAddr::from_parts(id, addrs))
}

#[cfg(test)]
mod tests {
    use super::*;
    use iroh::SecretKey;

    #[test]
    fn an_address_is_read_back_as_it_was_written() {
        let id = SecretKey::generate().public();
        let addr = EndpointAddr::new(id)
            .with_relay_url("https://relay.example.com/".parse().unwrap())
            .with_ip_addr("192.0.2.1:4433".parse().unwrap())
            .with_ip_addr("[2001:db8::1]:4433".parse().unwrap());
        let text = format(&addr);
        assert_eq!(parse(&text), Some(addr));
    }

    #[test]
    fn the_unknown_parts_are_skipped() {
        let id = SecretKey::generate().public();
        let parsed = parse(&format!("{id} something-else 192.0.2.1:4433")).unwrap();
        assert_eq!(parsed.id, id);
        assert_eq!(parsed.ip_addrs().count(), 1);
        assert_eq!(parse("not-an-id"), None);
        assert_eq!(parse(""), None);
    }
}
