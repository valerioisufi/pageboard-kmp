# The relay of DrawView

The devices of the users meet through a relay when they are not on the same network: the relay
tells each device where the others are, helps them reach each other directly (hole punching), and
carries their traffic when they cannot. The traffic is encrypted twice: by iroh between the
devices, and with the key of each document inside it. The relay reads none of it. See
[docs/internet-sync.md](../../docs/internet-sync.md).

The relay is the `iroh-relay` server of [iroh](https://github.com/n0-computer/iroh), of the
version the bridge uses (`native/iroh-bridge/Cargo.lock`), run as a systemd service:

| File | What |
|---|---|
| `install.sh` | Installs or updates the relay on the machine: the release of n0, checked against its SHA-256 |
| `iroh-relay.toml` | Its configuration: HTTPS with a Let's Encrypt certificate, QUIC address discovery, metrics on the loopback, access by token |
| `iroh-relay.service` | The systemd unit: an unprivileged user, ports 80 and 443 through a capability |

## What it needs

* A Linux machine with systemd and a public IPv4 address, always on. The relay needs little:
  1 vCPU and 1 GB of memory are plenty.
* A DNS name pointing at it (Let's Encrypt gives the certificate for a name). A free dynamic DNS
  name does it, for example `yourname.duckdns.org` from [Duck DNS](https://www.duckdns.org).
* These ports open to the internet:

  | Port | Protocol | What |
  |---|---|---|
  | 80 | TCP | The challenges of Let's Encrypt |
  | 443 | TCP | The relay (HTTPS) |
  | 7842 | UDP | QUIC address discovery: a device learns the address the internet sees it at |

## On a free e2-micro of Google Cloud

The free tier of Google Cloud includes one e2-micro machine, only in `us-west1`, `us-central1`
or `us-east1`, with 30 GB of standard disk and 1 GB of outbound traffic a month (October 2026;
check [the current terms](https://cloud.google.com/free/docs/compute-getting-started)).

1. **Project and budget.** Create a project in the Google Cloud console, then a budget alert
   (*Billing → Budgets & alerts*, for example 1 €). Beyond the free tier, the traffic and the
   external address are billed.
2. **Machine.** *Compute Engine → VM instances → Create instance*:
   * region `us-central1` (or `us-west1`, `us-east1`), machine type `e2-micro`;
   * boot disk Debian 13, *standard persistent disk*, 10–30 GB;
   * networking: an external IPv4 address. Make it *static* (*VPC network → IP addresses →
     Reserve*), so that the DNS name keeps pointing at it. Check its price: a static address
     attached to a running machine of the free tier may still be billed.
3. **Firewall.** *VPC network → Firewall → Create firewall rule*: ingress, to the machine (a
   network tag, for example `iroh-relay`), from `0.0.0.0/0`, TCP `80,443` and UDP `7842`. Give
   the machine the tag.
4. **DNS name.** Point the name at the external address (on Duck DNS: the `ip` of the domain).
   Check it: `dig +short yourname.duckdns.org` gives the address.
5. **Install.** From your computer, copy this directory to the machine and run the script:

   ```bash
   gcloud compute scp --recurse tools/relay <machine>:~/relay --zone <zone>
   gcloud compute ssh <machine> --zone <zone> --command "cd relay && sudo ./install.sh yourname.duckdns.org you@example.com"
   ```

   The script prints the address of the relay (`https://yourname.duckdns.org`) and its access
   token. The certificate comes from Let's Encrypt at the first connection: the log says so
   (`journalctl -u iroh-relay -f`).
6. **Applications.** In the settings of PencilNotes (synchronization over the internet), give
   the address and the token. A library application gives them in its `RelayConfig`.

## Keeping it

* **The traffic.** The counters are on the loopback of the machine:

  ```bash
  gcloud compute ssh <machine> --zone <zone> --command "curl -s localhost:9090/metrics | grep -E 'bytes_(sent|recv)'"
  ```

  Most connections go direct after a few hundred milliseconds. What goes through the relay is
  the traffic of the devices that cannot reach each other (some mobile networks), and it counts
  as outbound traffic of the machine. If 1 GB a month is not enough, the relay moves to another
  machine (another provider, a region in Europe) and the applications get its new address.
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

These checks ran over plain HTTP on this Mac: the certificate of Let's Encrypt, port 443 and the
QUIC address discovery are checked on the machine (phase 7 of the plan).
