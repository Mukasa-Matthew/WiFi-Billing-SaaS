# Deployment helpers

Host-level operational tools, not application runtime assets. Existing paths are retained to avoid breaking sudoers, forced-command and operator references.

- `activate-cloudserv-wg-management.sh`: installs the narrow WireGuard sudoers rule.
- `wg-cloudserv`: SSH wrapper from application host to WireGuard hub.
- `wifi-wg-peer`: forced-command target for constrained peer add/remove operations.
- `verify-voucher-radius.sh`: read-only FreeRADIUS/UTC/expiration validation.

Review hard-coded usernames, addresses and key paths for the target environment. Do not copy these blindly. Preserve executable permissions and validate sudoers with `visudo`.
