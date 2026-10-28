#!/usr/bin/env bash
# ==============================================================================
# WiFi-Billing SaaS — Safe Versioned Deployment Helpers Installer
# Copies versioned deployment scripts and systemd templates into place
# WITHOUT touching the live /var/www/wifi-billing checkout or running services.
# ==============================================================================
set -euo pipefail

SOURCE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET_DEPLOY_DIR="/var/www/wifi-billing/deployment"
BLUE_GREEN_DIR="${TARGET_DEPLOY_DIR}/blue-green"
SYSTEMD_TARGET_DIR="/etc/systemd/system"

echo "=== Installing Versioned Deployment Helpers to ${TARGET_DEPLOY_DIR} ==="

mkdir -p "${BLUE_GREEN_DIR}" "${TARGET_DEPLOY_DIR}/slots"

# 1. Install blue-green deployment scripts
cp -v "${SOURCE_DIR}/blue-green/blue-green-deploy.sh" "${BLUE_GREEN_DIR}/"
cp -v "${SOURCE_DIR}/blue-green/blue-green-rollback.sh" "${BLUE_GREEN_DIR}/"
cp -v "${SOURCE_DIR}/blue-green/stage-release-artifacts.sh" "${BLUE_GREEN_DIR}/"
cp -v "${SOURCE_DIR}/blue-green/upgrade-worker.sh" "${BLUE_GREEN_DIR}/"
chmod 755 "${BLUE_GREEN_DIR}"/*.sh

# 2. Install restricted root helpers if updated
if [ -f "${SOURCE_DIR}/blue-green/deploy-nginx-control.sh" ]; then
    sudo cp -v "${SOURCE_DIR}/blue-green/deploy-nginx-control.sh" "/usr/local/bin/deploy-nginx-control"
    sudo chmod 755 "/usr/local/bin/deploy-nginx-control"
    sudo chown root:root "/usr/local/bin/deploy-nginx-control"
fi

if [ -f "${SOURCE_DIR}/blue-green/deploy-service-control.sh" ]; then
    sudo cp -v "${SOURCE_DIR}/blue-green/deploy-service-control.sh" "/usr/local/bin/deploy-service-control"
    sudo chmod 755 "/usr/local/bin/deploy-service-control"
    sudo chown root:root "/usr/local/bin/deploy-service-control"
fi

# 3. Install Blue systemd units
echo "=== Installing Blue systemd unit files ==="
sudo cp -v "${SOURCE_DIR}/systemd/wifi-backend-blue.service" "${SYSTEMD_TARGET_DIR}/"
sudo cp -v "${SOURCE_DIR}/systemd/wifi-frontend-blue.service" "${SYSTEMD_TARGET_DIR}/"
sudo chmod 644 "${SYSTEMD_TARGET_DIR}/wifi-backend-blue.service" "${SYSTEMD_TARGET_DIR}/wifi-frontend-blue.service"
sudo chown root:root "${SYSTEMD_TARGET_DIR}/wifi-backend-blue.service" "${SYSTEMD_TARGET_DIR}/wifi-frontend-blue.service"

sudo systemctl daemon-reload
echo "=== Deployment helpers and Blue units installed successfully! ==="
