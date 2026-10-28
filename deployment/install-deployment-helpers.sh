#!/usr/bin/env bash
# ==============================================================================
# WiFi-Billing SaaS — Secure Production Deployment Infrastructure Installer
# Executed by Administrator with sudo permissions.
# ==============================================================================
set -euo pipefail

SOURCE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET_OPT_DIR="/opt/airpairy/deployment"
BLUE_GREEN_DIR="${TARGET_OPT_DIR}/blue-green"
SLOTS_CONFIG_DIR="${TARGET_OPT_DIR}/slots"
STATE_DIR="${TARGET_OPT_DIR}/state"
SYSTEMD_TARGET_DIR="/etc/systemd/system"
EXPECTED_COMMIT="c3260c9f878d03cff8f1fc2c794e39c58a4ce415"

echo "=================================================================="
echo " AirPairy Secure Deployment Infrastructure Setup"
echo " Authoritative Target Commit : ${EXPECTED_COMMIT}"
echo " Source Directory            : ${SOURCE_DIR}"
echo " Target Infrastructure       : ${TARGET_OPT_DIR}"
echo "=================================================================="

# 1. Source verification
REQUIRED_FILES=(
    "${SOURCE_DIR}/blue-green/blue-green-deploy.sh"
    "${SOURCE_DIR}/blue-green/blue-green-rollback.sh"
    "${SOURCE_DIR}/blue-green/stage-release-artifacts.sh"
    "${SOURCE_DIR}/blue-green/upgrade-worker.sh"
    "${SOURCE_DIR}/blue-green/deploy-nginx-control.sh"
    "${SOURCE_DIR}/blue-green/deploy-service-control.sh"
    "${SOURCE_DIR}/systemd/wifi-backend-blue.service"
    "${SOURCE_DIR}/systemd/wifi-frontend-blue.service"
)

for rf in "${REQUIRED_FILES[@]}"; do
    if [ ! -f "${rf}" ]; then
        echo "ERROR: Required source file missing: ${rf}" >&2
        exit 1
    fi
done

# 2. Directory structure creation with least-privilege ownership
echo "Creating secure directory layout under ${TARGET_OPT_DIR}..."
mkdir -p "${BLUE_GREEN_DIR}" "${SLOTS_CONFIG_DIR}" "${STATE_DIR}"

# Infrastructure scripts: owned by root:root, read/exec only (755)
chown -R root:root "${TARGET_OPT_DIR}"
chmod 755 "${TARGET_OPT_DIR}"
chmod 755 "${BLUE_GREEN_DIR}"

# Runtime state and slot environment configs: owned by matthew:matthew (750 / 770)
chown matthew:matthew "${SLOTS_CONFIG_DIR}" "${STATE_DIR}"
chmod 770 "${SLOTS_CONFIG_DIR}" "${STATE_DIR}"

# Initialize active slot state file if not already present
if [ ! -f "${STATE_DIR}/active_slot.txt" ]; then
    echo "green" > "${STATE_DIR}/active_slot.txt"
    chown matthew:matthew "${STATE_DIR}/active_slot.txt"
    chmod 660 "${STATE_DIR}/active_slot.txt"
fi

# 3. Install Root-Owned Executables
echo "Installing deployment scripts to ${BLUE_GREEN_DIR} (root:root, 755)..."
cp "${SOURCE_DIR}/blue-green/blue-green-deploy.sh" "${BLUE_GREEN_DIR}/"
cp "${SOURCE_DIR}/blue-green/blue-green-rollback.sh" "${BLUE_GREEN_DIR}/"
cp "${SOURCE_DIR}/blue-green/stage-release-artifacts.sh" "${BLUE_GREEN_DIR}/"
cp "${SOURCE_DIR}/blue-green/upgrade-worker.sh" "${BLUE_GREEN_DIR}/"
chown root:root "${BLUE_GREEN_DIR}"/*.sh
chmod 755 "${BLUE_GREEN_DIR}"/*.sh

# 4. Install Restricted Root Helpers in /usr/local/bin
echo "Installing restricted root helpers to /usr/local/bin (root:root, 755)..."
cp "${SOURCE_DIR}/blue-green/deploy-nginx-control.sh" "/usr/local/bin/deploy-nginx-control"
chown root:root "/usr/local/bin/deploy-nginx-control"
chmod 755 "/usr/local/bin/deploy-nginx-control"

cp "${SOURCE_DIR}/blue-green/deploy-service-control.sh" "/usr/local/bin/deploy-service-control"
chown root:root "/usr/local/bin/deploy-service-control"
chmod 755 "/usr/local/bin/deploy-service-control"

# 5. Install Blue Systemd Service Units
echo "Installing Blue systemd units to ${SYSTEMD_TARGET_DIR} (root:root, 644)..."
cp "${SOURCE_DIR}/systemd/wifi-backend-blue.service" "${SYSTEMD_TARGET_DIR}/"
cp "${SOURCE_DIR}/systemd/wifi-frontend-blue.service" "${SYSTEMD_TARGET_DIR}/"
chown root:root "${SYSTEMD_TARGET_DIR}/wifi-backend-blue.service" "${SYSTEMD_TARGET_DIR}/wifi-frontend-blue.service"
chmod 644 "${SYSTEMD_TARGET_DIR}/wifi-backend-blue.service" "${SYSTEMD_TARGET_DIR}/wifi-frontend-blue.service"

# Reload systemd manager
echo "Reloading systemd daemon..."
systemctl daemon-reload

echo "=================================================================="
echo " Deployment infrastructure setup successfully completed!"
echo " Scripts : ${BLUE_GREEN_DIR} (root:root 755)"
echo " State   : ${STATE_DIR} (matthew:matthew 770)"
echo " Slots   : ${SLOTS_CONFIG_DIR} (matthew:matthew 770)"
echo " Systemd : ${SYSTEMD_TARGET_DIR}/wifi-*-blue.service"
echo "=================================================================="
