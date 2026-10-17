#!/usr/bin/env bash
# ==============================================================================
# Secure SSH Port Forwarding Tunnel to cloudserv (For Infrastructure Integration)
# Forwards:
#   - 127.0.0.1:15432 -> cloudserv 127.0.0.1:5432 (PostgreSQL 16 wifi_app / wifi_billing)
#   - 127.0.0.1:18085 -> cloudserv 127.0.0.1:8085 (wifi-provisioning daemon)
# ==============================================================================
set -euo pipefail

echo "Establishing secure loopback SSH tunnel to cloudserv..."
echo "  Local 127.0.0.1:15432 -> cloudserv 127.0.0.1:5432 (PostgreSQL 16)"
echo "  Local 127.0.0.1:18085 -> cloudserv 127.0.0.1:8085 (wifi-provisioning)"
echo "Press Ctrl+C to close the tunnel."

exec ssh -N \
  -L 127.0.0.1:15432:127.0.0.1:5432 \
  -L 127.0.0.1:18085:127.0.0.1:8085 \
  cloudserv
