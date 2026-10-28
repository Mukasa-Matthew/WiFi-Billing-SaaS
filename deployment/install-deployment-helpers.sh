#!/usr/bin/env bash
# ==============================================================================
# WiFi-Billing SaaS — Isolated Deployment Infrastructure Installer
# Installs versioned deployment scripts and helpers into /opt/airpairy/deployment
# and systemd units into /etc/systemd/system WITHOUT touching the live application
# Git checkout at /var/www/wifi-billing or any running services.
# ==============================================================================
set -euo pipefail

SOURCE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET_OPT_DIR="/opt/airpairy/deployment"
BLUE_GREEN_DIR="${TARGET_OPT_DIR}/blue-green"
SLOTS_CONFIG_DIR="/opt/airpairy/deployment/slots"
SYSTEMD_TARGET_DIR="/etc/systemd/system"
BACKUP_STAGE_DIR="/opt/airpairy/deployment/.backup-$(date +%Y%m%d%H%M%S)"

echo "=== AirPairy Deployment Infrastructure Installation ==="
echo " Source Directory : ${SOURCE_DIR}"
echo " Target Directory : ${TARGET_OPT_DIR}"

# 1. Verify required source files exist before making any modifications
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
        echo "ERROR: Required installation source file missing: ${rf}" >&2
        exit 1
    fi
done

# 2. Stage backup of existing /opt/airpairy/deployment if present
if [ -d "${TARGET_OPT_DIR}" ]; then
    echo "Creating safety backup of existing deployment infrastructure in ${BACKUP_STAGE_DIR}..."
    sudo mkdir -p "${BACKUP_STAGE_DIR}"
    sudo cp -rp "${TARGET_OPT_DIR}/." "${BACKUP_STAGE_DIR}/" 2>/dev/null || true
fi

# 3. Create target directories with explicit ownership
sudo mkdir -p "${BLUE_GREEN_DIR}" "${SLOTS_CONFIG_DIR}"
sudo chown -R matthew:matthew /opt/airpairy

# 4. Install Blue-Green deployment and staging scripts
echo "Installing deployment scripts to ${BLUE_GREEN_DIR}..."
cp -v "${SOURCE_DIR}/blue-green/blue-green-deploy.sh" "${BLUE_GREEN_DIR}/"
cp -v "${SOURCE_DIR}/blue-green/blue-green-rollback.sh" "${BLUE_GREEN_DIR}/"
cp -v "${SOURCE_DIR}/blue-green/stage-release-artifacts.sh" "${BLUE_GREEN_DIR}/"
cp -v "${SOURCE_DIR}/blue-green/upgrade-worker.sh" "${BLUE_GREEN_DIR}/"
chmod 755 "${BLUE_GREEN_DIR}"/*.sh

# 5. Install restricted root helpers to /usr/local/bin
echo "Installing restricted root helpers to /usr/local/bin..."
sudo cp -v "${SOURCE_DIR}/blue-green/deploy-nginx-control.sh" "/usr/local/bin/deploy-nginx-control"
sudo chmod 755 "/usr/local/bin/deploy-nginx-control"
sudo chown root:root "/usr/local/bin/deploy-nginx-control"

sudo cp -v "${SOURCE_DIR}/blue-green/deploy-service-control.sh" "/usr/local/bin/deploy-service-control"
sudo chmod 755 "/usr/local/bin/deploy-service-control"
sudo chown root:root "/usr/local/bin/deploy-service-control"

# 6. Install Blue systemd unit files
echo "Installing Blue systemd unit files to ${SYSTEMD_TARGET_DIR}..."
sudo cp -v "${SOURCE_DIR}/systemd/wifi-backend-blue.service" "${SYSTEMD_TARGET_DIR}/"
sudo cp -v "${SOURCE_DIR}/systemd/wifi-frontend-blue.service" "${SYSTEMD_TARGET_DIR}/"
sudo chmod 644 "${SYSTEMD_TARGET_DIR}/wifi-backend-blue.service" "${SYSTEMD_TARGET_DIR}/wifi-frontend-blue.service"
sudo chown root:root "${SYSTEMD_TARGET_DIR}/wifi-backend-blue.service" "${SYSTEMD_TARGET_DIR}/wifi-frontend-blue.service"

# Reload systemd definitions without restarting any active units
echo "Reloading systemd manager configuration..."
sudo systemctl daemon-reload

echo "=================================================================="
echo " AirPairy deployment infrastructure successfully installed!"
echo " Location: ${TARGET_OPT_DIR}"
echo " Running services remained completely untouched."
echo "=================================================================="
