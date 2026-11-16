# The relay of DrawView

The devices of the users meet through a relay when they are not on the same network: the relay
tells each device where the others are, helps them reach each other directly (hole punching), and
carries their traffic when they cannot. The traffic is encrypted twice: by iroh between the
devices, and with the key of each document inside it. The relay reads none of it. See
[docs/plans/internet-sync.md](../../docs/plans/internet-sync.md).

The relay is the `iroh-relay` server of [iroh](https://github.com/n0-computer/iroh), of the
version the bridge uses (`native/iroh-bridge/Cargo.lock`), run as a systemd service:

| File | What |
|---|---|
| `install.sh` | Installs or updates the relay on the machine: the release of n0, checked against its SHA-256; opens its ports in the firewall of the machine when the image closes them (Oracle Cloud) |
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

## On an Always Free machine of Oracle Cloud

The Always Free resources of Oracle Cloud include two `VM.Standard.E2.1.Micro` machines (AMD):
1/8 of an OCPU that can use more for short times, 1 GB of memory, 50 Mbps to the internet and
10 TB of outbound traffic a month, in the home region of the account (October 2026; check
[the current terms](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm)).

1. **Account.**
   * The free resources are in the home region only, chosen when the account is made and never
     changed. A region near the users (Milan, Frankfurt) keeps short the way through the relay.
   * Oracle stops an Always Free machine it deems idle: over 7 days, its CPU under 20 % (95th
     percentile) and its network under 20 %. A relay for a few devices is idle by that measure.
     An account upgraded to Pay As You Go (*Billing → Upgrade and manage payment*) keeps its
     machines running, and its Always Free resources stay free. Add a budget alert then
     (*Billing → Budgets*, for example 1 €), against anything made by mistake beyond them.
     Without the upgrade, a stopped machine is started again from the console, with its address.
2. **Machine.** *Compute → Instances → Create instance*:
   * image *Canonical Ubuntu 24.04*, its *Minimal* build where it is listed (lighter in 1 GB of
     memory);
   * shape *VM.Standard.E2.1.Micro* (*Change shape → Virtual machine → Specialty and previous
     generation*), marked *Always Free-eligible*. In a region with several availability domains
     it exists in one of them only: choose the one the console marks;
   * networking: a new virtual cloud network with a public subnet, and *Automatically assign
     public IPv4 address*. The address stays with the machine while it is stopped, and goes when
     the machine is terminated (*Networking → Reserved public IPs* keeps one apart from the
     machines);
   * SSH keys: your public key (`~/.ssh/id_ed25519.pub`; `ssh-keygen -t ed25519` makes one);
   * boot volume: the default size (about 50 GB) is within the 200 GB of free volumes.
3. **Firewall of the network.** The security list of the subnet (*Networking → Virtual cloud
   networks → the network → Security → Default Security List → Security rules → Add Ingress
   Rules*): source `0.0.0.0/0`, protocol TCP, destination ports `80,443`; then source
   `0.0.0.0/0`, protocol UDP, destination port `7842`. SSH (22) is open already. The images of
   Oracle also close the ports in the machine (iptables): `install.sh` opens them there.
4. **DNS name.** Point the name at the public address of the machine (on Duck DNS: the `ip` of
   the domain). Check it: `dig +short yourname.duckdns.org` gives the address.
5. **Install.** From your computer, copy this directory to the machine and run the script (the
   user of the Ubuntu images is `ubuntu`):

   ```bash
   scp -r tools/relay ubuntu@<address>:relay
   ssh ubuntu@<address> "cd relay && sudo ./install.sh yourname.duckdns.org you@example.com"
   ```

   The script prints the address of the relay (`https://yourname.duckdns.org`) and its access
   token. The certificate comes from Let's Encrypt at the first connection: the log says so
   (`journalctl -u iroh-relay -f`). Check it from your computer:
   `curl https://yourname.duckdns.org/healthz` answers with `"status":"ok"`.
6. **Applications.** In the settings of PencilNotes (synchronization over the internet), give
   the address and the token. A library application gives them in its `RelayConfig`.

## On another machine

Any provider does, with the ports above open in its firewall: create the machine, point the
name at it, and run `install.sh` on it as in steps 4–6.

## Keeping it

* **The traffic.** The counters are on the loopback of the machine:

  ```bash
  ssh ubuntu@<address> "curl -s localhost:9090/metrics | grep -E 'bytes_(sent|recv)'"
  ```

  Most connections go direct after a few hundred milliseconds. What goes through the relay is
  the traffic of the devices that cannot reach each other (some mobile networks), and it counts
  as outbound traffic of the machine: far less than the 10 TB a month of the free machine.
* **The machine.** Ubuntu installs its security updates by itself; when it asks for a restart
  (`ssh ubuntu@<address>` says so), `sudo reboot`: the relay starts with the machine. If the
  relay stops answering, check that Oracle has not stopped the machine (step 1).
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
* `install.sh` in a container of Ubuntu 24.04 (amd64) with the iptables rules of the images of
  Oracle: it installs curl, the release (checked) and the configuration, puts the ports of the
  relay before the REJECT of INPUT and saves them; run again, it adds nothing.

These checks ran on this Mac: the certificate of Let's Encrypt, port 443 and the QUIC address
discovery are checked on the machine (phase 7 of the plan).
