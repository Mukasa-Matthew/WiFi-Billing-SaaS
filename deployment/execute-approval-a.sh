#!/usr/bin/env bash
# ==============================================================================
# AirPairy — Administrator Interactive Approval A Installation Script
# Location: /opt/airpairy/install/execute-approval-a.sh
# Run with interactive sudo:
#   sudo /opt/airpairy/install/execute-approval-a.sh
# ==============================================================================
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
    echo "ERROR: This script must be run as root (via sudo)." >&2
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET_COMMIT="66433b2cfe6960592b2317ce4107f4e59358a275"
OPT_DEPLOY="/opt/airpairy/deployment"
BLUE_GREEN_DIR="${OPT_DEPLOY}/blue-green"
SLOTS_DIR="${OPT_DEPLOY}/slots"
STATE_DIR="${OPT_DEPLOY}/state"
SYSTEMD_TARGET_DIR="/etc/systemd/system"

echo "=================================================================="
echo " AirPairy — Approval A: Root-Controlled Infrastructure Setup"
echo " Target Commit : ${TARGET_COMMIT}"
echo " Timestamp     : $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
echo " Running as    : $(whoami)"
echo "=================================================================="

# 1. Assert preflight production health
echo "[1/7] Checking live production services..."
if ! systemctl is-active --quiet wifi-frontend-green.service || \
   ! systemctl is-active --quiet wifi-backend-green.service || \
   ! systemctl is-active --quiet wifi-worker.service; then
    echo "CRITICAL ERROR: Live Green or Worker service is not active! Aborting." >&2
    exit 1
fi
echo "  Passed: Green frontend, backend, and dedicated worker are active."

# 2. Confirm retired legacy services are inactive
echo "[2/7] Verifying retired legacy services are inactive..."
if systemctl is-active --quiet wifi-backend.service || \
   systemctl is-active --quiet wifi-frontend.service; then
    echo "CRITICAL ERROR: Legacy service on port 8080 or 3000 is still active! Aborting." >&2
    exit 1
fi
echo "  Passed: Legacy services on ports 8080 and 3000 are inactive."

# 3. Create root-owned infrastructure directories with restricted state dirs
echo "[3/7] Setting up /opt/airpairy/deployment directory layout..."
mkdir -p "${BLUE_GREEN_DIR}" "${SLOTS_DIR}" "${STATE_DIR}"

# Deployment executables owned by root:root, read/exec only (755)
chown -R root:root "${OPT_DEPLOY}"
chmod 755 "${OPT_DEPLOY}"
chmod 755 "${BLUE_GREEN_DIR}"

# Writable state and slot config directories owned by matthew:matthew (770)
chown matthew:matthew "${SLOTS_DIR}" "${STATE_DIR}"
chmod 770 "${SLOTS_DIR}" "${STATE_DIR}"

# Initialize active_slot.txt tracking file to 'green' if absent
if [ ! -f "${STATE_DIR}/active_slot.txt" ]; then
    echo "green" > "${STATE_DIR}/active_slot.txt"
    chown matthew:matthew "${STATE_DIR}/active_slot.txt"
    chmod 660 "${STATE_DIR}/active_slot.txt"
fi

# 4. Install root-owned deployment scripts to /opt/airpairy/deployment/blue-green/
echo "[4/7] Installing deployment scripts to ${BLUE_GREEN_DIR} (root:root, 755)..."
cp "${SCRIPT_DIR}/blue-green/blue-green-deploy.sh" "${BLUE_GREEN_DIR}/"
cp "${SCRIPT_DIR}/blue-green/blue-green-rollback.sh" "${BLUE_GREEN_DIR}/"
cp "${SCRIPT_DIR}/blue-green/stage-release-artifacts.sh" "${BLUE_GREEN_DIR}/"
cp "${SCRIPT_DIR}/blue-green/upgrade-worker.sh" "${BLUE_GREEN_DIR}/"
chown root:root "${BLUE_GREEN_DIR}"/*.sh
chmod 755 "${BLUE_GREEN_DIR}"/*.sh

# Install restricted root helpers to /usr/local/bin
cp "${SCRIPT_DIR}/blue-green/deploy-nginx-control.sh" "/usr/local/bin/deploy-nginx-control"
chown root:root "/usr/local/bin/deploy-nginx-control"
chmod 755 "/usr/local/bin/deploy-nginx-control"

cp "${SCRIPT_DIR}/blue-green/deploy-service-control.sh" "/usr/local/bin/deploy-service-control"
chown root:root "/usr/local/bin/deploy-service-control"
chmod 755 "/usr/local/bin/deploy-service-control"

# 5. Install Blue systemd unit files to /etc/systemd/system/
echo "[5/7] Installing Blue systemd service definitions (root:root, 644)..."
cp "${SCRIPT_DIR}/systemd/wifi-backend-blue.service" "${SYSTEMD_TARGET_DIR}/"
cp "${SCRIPT_DIR}/systemd/wifi-frontend-blue.service" "${SYSTEMD_TARGET_DIR}/"
chown root:root "${SYSTEMD_TARGET_DIR}/wifi-backend-blue.service" "${SYSTEMD_TARGET_DIR}/wifi-frontend-blue.service"
chmod 644 "${SYSTEMD_TARGET_DIR}/wifi-backend-blue.service" "${SYSTEMD_TARGET_DIR}/wifi-frontend-blue.service"

# Reload systemd definitions without restarting any active units
systemctl daemon-reload

# 6. Service enablement management (prevent reboot port collision)
echo "[6/7] Managing systemd unit startup enablement..."
# Disable legacy autostart so ports 8080/3000 never collide on reboot
systemctl disable wifi-backend.service wifi-frontend.service

# Ensure Green services and dedicated worker persist across reboots
systemctl enable wifi-frontend-green.service wifi-backend-green.service wifi-worker.service

# Confirm Blue services are NOT enabled
if systemctl is-enabled --quiet wifi-backend-blue.service 2>/dev/null || \
   systemctl is-enabled --quiet wifi-frontend-blue.service 2>/dev/null; then
    systemctl disable wifi-backend-blue.service wifi-frontend-blue.service
fi

# 7. Final port verification
echo "[7/7] Verifying standby ports 8080 and 3000..."
if ss -tlnp | grep -E ':8080|:3000' >/dev/null 2>&1; then
    echo "CRITICAL ERROR: Standby port 8080 or 3000 is listening! Aborting." >&2
    exit 1
fi
echo "  Passed: Ports 8080 and 3000 are completely free."

echo "=================================================================="
echo " Approval A Installation Successfully Completed!"
echo " Deployment Directory : ${BLUE_GREEN_DIR} (root:root 755)"
echo " State Directory      : ${STATE_DIR} (matthew:matthew 770)"
echo " Blue Services        : Installed, INACTIVE, and DISABLED"
echo " Live Production      : Untouched on Green (:3001/:8081)"
echo "=================================================================="
