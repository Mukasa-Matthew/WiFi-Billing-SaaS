#!/bin/sh
set -eu

rule='matthew ALL=(root) NOPASSWD: /usr/bin/wg set wg0 peer *'
tmp=$(mktemp)
trap 'rm -f "$tmp"' EXIT
printf '%s\n' "$rule" > "$tmp"
chmod 440 "$tmp"
visudo -cf "$tmp"
install -o root -g root -m 440 "$tmp" /etc/sudoers.d/wifi-billing-wireguard

sudo -u matthew sudo -n -l /usr/bin/wg set wg0 peer test remove >/dev/null
echo "Cloudserv WireGuard peer management activated successfully."
