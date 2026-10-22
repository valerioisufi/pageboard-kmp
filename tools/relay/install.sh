#!/usr/bin/env bash
# Installs (or updates) the relay of DrawView on a Linux machine with systemd: the iroh-relay
# release of n0 of the version the bridge uses, checked against its SHA-256, run as the service
# iroh-relay. Run it as root on the machine, from this directory:
#
#   sudo ./install.sh <hostname> <contact email>
#
#   hostname: the DNS name pointing at the machine (the certificate of Let's Encrypt is for it)
#   contact:  the email Let's Encrypt writes to about the certificate
#
# It prints the address of the relay and its access token, for the settings of the applications.
# The token is made once (/etc/iroh-relay/token.env) and kept by the updates.
set -euo pipefail

# The version of iroh in native/iroh-bridge/Cargo.lock, and the SHA-256 of its release archives
VERSION="1.4.1"
SHA256_X86_64="75254641e3fad7fcbac6dee4d485556fbe06ef708340a9d8810469270ba626b8"
SHA256_AARCH64="3fc0c2d67c1cd66250bf82f3e2f17a985b7081a3d272186bad9d63e3f2347b38"

if [[ $# -ne 2 ]]; then
    sed -n '2,13p' "$0"
    exit 1
fi
HOSTNAME_="$1"
CONTACT="$2"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

case "$(uname -m)" in
    x86_64) ARCH="x86_64"; SHA256="$SHA256_X86_64" ;;
    aarch64 | arm64) ARCH="aarch64"; SHA256="$SHA256_AARCH64" ;;
    *) echo "Unsupported architecture: $(uname -m)" >&2; exit 1 ;;
esac

# The binary of the release, checked
ARCHIVE="iroh-relay-v$VERSION-$ARCH-unknown-linux-musl.tar.gz"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
curl -fsSL -o "$WORK/$ARCHIVE" "https://github.com/n0-computer/iroh/releases/download/v$VERSION/$ARCHIVE"
echo "$SHA256  $WORK/$ARCHIVE" | sha256sum -c -
tar -xzf "$WORK/$ARCHIVE" -C "$WORK"
install -m 0755 "$(find "$WORK" -type f -name iroh-relay | head -n 1)" /usr/local/bin/iroh-relay

# Its user, its configuration and its token
id iroh-relay >/dev/null 2>&1 || useradd --system --no-create-home --shell /usr/sbin/nologin iroh-relay
install -d -m 0755 /etc/iroh-relay
install -d -m 0750 -o iroh-relay -g iroh-relay /var/lib/iroh-relay /var/lib/iroh-relay/certs
sed -e "s|@HOSTNAME@|$HOSTNAME_|" -e "s|@CONTACT@|$CONTACT|" "$HERE/iroh-relay.toml" > /etc/iroh-relay/iroh-relay.toml
if [[ ! -f /etc/iroh-relay/token.env ]]; then
    umask 077
    echo "IROH_RELAY_ACCESS_TOKEN=$(head -c 32 /dev/urandom | base64 | tr -d '/+=' | head -c 40)" > /etc/iroh-relay/token.env
fi
chown root:iroh-relay /etc/iroh-relay/token.env
chmod 0640 /etc/iroh-relay/token.env

install -m 0644 "$HERE/iroh-relay.service" /etc/systemd/system/iroh-relay.service
systemctl daemon-reload
systemctl enable iroh-relay >/dev/null
systemctl restart iroh-relay

echo
echo "The relay of DrawView runs (iroh-relay $VERSION). In the settings of the applications:"
echo "  address: https://$HOSTNAME_"
echo "  token:   $(sed -n 's/^IROH_RELAY_ACCESS_TOKEN=//p' /etc/iroh-relay/token.env)"
echo "Its log: journalctl -u iroh-relay -f"
