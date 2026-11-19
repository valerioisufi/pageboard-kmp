# The relay of Pageboard

The devices of the users meet through a relay when they are not on the same network: the relay
tells each device where the others are, helps them reach each other directly (hole punching), and
carries their traffic when they cannot. The traffic is encrypted twice: by iroh between the
devices, and with the key of each document inside it. The relay reads none of it. See
[docs/plans/internet-sync.md](../../docs/plans/internet-sync.md).

The relay is the `iroh-relay` server of [iroh](https://github.com/n0-computer/iroh), of the
version the bridge uses (`native/iroh-bridge/Cargo.lock`), run as a systemd service:

| File | What |
|---|---|
| `install.sh` | Installs or updates the relay on the machine: the release of n0, checked against its SHA-256; opens its ports in the firewall of the machine when the image closes them |
| `iroh-relay.toml` | Its configuration: HTTPS with a Let's Encrypt certificate, QUIC address discovery, metrics on the loopback, access by token |
| `iroh-relay.service` | The systemd unit: an unprivileged user, ports 80 and 443 through a capability |

## What it needs

* A Linux machine with systemd and a public IPv4 address, always on. The relay needs little:
  1 GB of memory and a fraction of a CPU are enough for the devices of a few users.
* A DNS name pointing at it (Let's Encrypt gives the certificate for a name). A free dynamic DNS
  name does it, for example `yourname.duckdns.org` from [Duck DNS](https://www.duckdns.org).
* These ports open to the internet:

  | Port | Protocol | What |
  |---|---|---|
  | 80 | TCP | The devices check it for captive portals (`/generate_204`) |
  | 443 | TCP | The relay (HTTPS). Let's Encrypt checks the name on it (TLS-ALPN-01) |
  | 7842 | UDP | QUIC address discovery: a device learns the address the internet sees it at |

## On a cloud machine

Any provider with a small Linux machine does: the relay needs a fraction of a CPU and 1 GB of
memory. Look at the outbound traffic a month that the plan includes: what cannot go direct goes
through the relay. A free tier may stop a machine it deems idle, as a relay of a few devices is:
read its terms.

1. **Machine.** Ubuntu 24.04 (a minimal image where there is one), a public IPv4 address that
   stays with the machine, and your SSH public key (`~/.ssh/id_ed25519.pub`;
   `ssh-keygen -t ed25519` makes one).
2. **Firewall of the provider.** Open TCP `80,443` and UDP `7842` to `0.0.0.0/0` in the firewall
   of the network (a security group, a security list, firewall rules). Some images also close the
   ports in the machine (iptables): `install.sh` opens them there.
3. **DNS name.** Point the name at the public address of the machine (on Duck DNS: the `ip` of
   the domain). Check it: `dig +short yourname.duckdns.org` gives the address.
4. **Install.** From your computer, copy this directory to the machine and run the script (the
   user of the Ubuntu images is often `ubuntu`):

   ```bash
   scp -r tools/relay ubuntu@<address>:relay
   ssh ubuntu@<address> "cd relay && sudo ./install.sh yourname.duckdns.org you@example.com"
   ```

   The script prints the address of the relay (`https://yourname.duckdns.org`) and its access
   token. The certificate comes from Let's Encrypt at the first connection: the log says so
   (`journalctl -u iroh-relay -f`). Check it from your computer:
   `curl https://yourname.duckdns.org/healthz` answers with `"status":"ok"`.
5. **Applications.** In the settings of PencilNotes (synchronization over the internet), give
   the address and the token. A library application gives them in its `RelayConfig`.

## Keeping it

* **The traffic.** The counters are on the loopback of the machine:

  ```bash
  ssh ubuntu@<address> "curl -s localhost:9090/metrics | grep -E 'bytes_(sent|recv)'"
  ```

  Most connections go direct after a few hundred milliseconds. What goes through the relay is
  the traffic of the devices that cannot reach each other (some mobile networks), and it counts
  as outbound traffic of the machine.
* **The machine.** Ubuntu installs its security updates by itself; when it asks for a restart
  (`ssh ubuntu@<address>` says so), `sudo reboot`: the relay starts with the machine. If the
  relay stops answering, check that the provider has not stopped the machine.
* **Updates.** When the bridge moves to another version of iroh, `install.sh` gets its version
  and the SHA-256 of its archives, from the `digest` of the assets of
  [the release](https://github.com/n0-computer/iroh/releases), and runs again on the machine.
  The token is kept.
* **The token.** It is in `/etc/iroh-relay/token.env` (`IROH_RELAY_ACCESS_TOKEN`). To change
  it, change the file and restart the service (`sudo systemctl restart iroh-relay`), then give
  the new one to the applications.

## Checked

* The configuration is read by `iroh-relay` 1.4.1.
* With the token of the configuration, two endpoints of the bridge meet through the relay by
  identifier and relay only. With another token, or none, they do not.
* The metrics answer on the loopback.
* `install.sh` in a container of Ubuntu 24.04 (amd64) with iptables rules that close every port
  but SSH: it installs curl, the release (checked) and the configuration, puts the ports of the
  relay before the REJECT of INPUT and saves them; run again, it adds nothing.

These checks ran on this Mac: the certificate of Let's Encrypt, port 443 and the QUIC address
discovery are checked on the machine (phase 7 of the plan).
