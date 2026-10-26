#!/usr/bin/env bash
# ==============================================================================
# WiFi-Billing SaaS — Standby Slot Decommissioning Script
# Author: WiFi Billing Engineering Team
#
# Safely shuts down the standby slot after the observation window has passed.
# ==============================================================================
set -euo pipefail

APP_DIR="/var/www/wifi-billing"
ACTIVE_SLOT_FILE="${APP_DIR}/deployment/active_slot.txt"

if [ ! -f "${ACTIVE_SLOT_FILE}" ]; then
    echo "ERROR: Active slot file not found. Cannot determine standby slot safely." >&2
    exit 1
fi

ACTIVE_SLOT="$(cat "${ACTIVE_SLOT_FILE}" | tr -d ' \n')"
if [ "${ACTIVE_SLOT}" = "blue" ]; then
    STANDBY_SLOT="green"
elif [ "${ACTIVE_SLOT}" = "green" ]; then
    STANDBY_SLOT="blue"
else
    echo "ERROR: Unknown active slot '${ACTIVE_SLOT}'" >&2
    exit 1
fi

echo "=================================================================="
echo " Decommissioning Standby Slot: ${STANDBY_SLOT} (Active: ${ACTIVE_SLOT})"
echo "=================================================================="

sudo /usr/local/bin/deploy-service-control stop "backend-${STANDBY_SLOT}" || true
sudo /usr/local/bin/deploy-service-control stop "frontend-${STANDBY_SLOT}" || true

echo "Standby slot ${STANDBY_SLOT} has been safely stopped."
