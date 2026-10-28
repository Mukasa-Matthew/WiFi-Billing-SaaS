#!/usr/bin/env bash
# ==============================================================================
# AirPairy — Interactive Approval A Execution Script
# To be executed on AppServer by the Administrator using interactive sudo:
#   sudo /path/to/execute-approval-a.sh
# ==============================================================================
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
    echo "ERROR: This script must be run as root (or via sudo)." >&2
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET_COMMIT="c3260c9f878d03cff8f1fc2c794e39c58a4ce415"

echo "=================================================================="
echo " Starting Approval A: Production Infrastructure Setup Only"
echo " Target Commit: ${TARGET_COMMIT}"
echo " Timestamp    : $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
echo "=================================================================="

# 1. Preflight: Check that Green and Worker services are active
echo "[1/6] Checking live production services..."
if ! systemctl is-active --quiet wifi-frontend-green.service || \
   ! systemctl is-active --quiet wifi-backend-green.service || \
   ! systemctl is-active --quiet wifi-worker.service; then
    echo "CRITICAL ERROR: Live Green or Worker service is not active! Aborting." >&2
    exit 1
fi
echo "  Passed: Green frontend, backend, and dedicated worker are active."

# 2. Confirm retired legacy services are inactive
echo "[2/6] Verifying retired legacy services are inactive..."
if systemctl is-active --quiet wifi-backend.service || \
   systemctl is-active --quiet wifi-frontend.service; then
    echo "CRITICAL ERROR: Legacy service is active on ports 8080/3000! Aborting." >&2
    exit 1
fi
echo "  Passed: Legacy services on ports 8080 and 3000 are inactive."

# 3. Disable legacy service autostart to prevent reboot port collision
echo "[3/6] Disabling legacy services autostart..."
systemctl disable wifi-backend.service wifi-frontend.service
echo "  Passed: Legacy services autostart disabled."

# 4. Ensure Green services and dedicated worker are enabled for reboot persistence
echo "[4/6] Ensuring active production services are enabled for reboot..."
systemctl enable wifi-frontend-green.service wifi-backend-green.service wifi-worker.service
echo "  Passed: Production Green and worker services enabled."

# 5. Run the secure installation of deployment helpers and Blue units
echo "[5/6] Installing root-owned deployment infrastructure..."
"${SCRIPT_DIR}/install-deployment-helpers.sh"
echo "  Passed: Deployment helpers and Blue unit definitions installed."

# 6. Verify standby ports remain completely free
echo "[6/6] Verifying standby ports 8080 and 3000 are free..."
if ss -tlnp | grep -E ':8080|:3000' >/dev/null 2>&1; then
    echo "CRITICAL ERROR: Standby port 8080 or 3000 is occupied!" >&2
    exit 1
fi
echo "  Passed: Ports 8080 and 3000 are completely free."

echo "=================================================================="
echo " Approval A Execution Successfully Completed!"
echo " Deployment infrastructure is ready at /opt/airpairy/deployment."
echo " Live production traffic remains untouched on Green (:3001/:8081)."
echo "=================================================================="
