#!/usr/bin/env bash
# ==============================================================================
# WiFi-Billing SaaS — Emergency Rollback Script
# Author: WiFi Billing Engineering Team
#
# Supports two distinct modes:
#   1. WARM ROLLBACK (during initial observation while previous slot is still running):
#      Swaps Nginx upstream back to the warm standby in ~150 ms.
#   2. COLD ROLLBACK (after previous slot was stopped):
#      Starts previous services, verifies readiness, and only then switches traffic.
#      Measured startup duration: ~18 to 28 seconds.
# ==============================================================================
set -euo pipefail

MODE="${1:-warm}" # default: warm
APP_DIR="/var/www/wifi-billing"
DEPLOY_DIR="${DEPLOY_DIR:-/opt/airpairy/deployment}"
STATE_DIR="${DEPLOY_DIR}/state"
ACTIVE_SLOT_FILE="${STATE_DIR}/active_slot.txt"
ROLLBACK_SLOT_FILE="${STATE_DIR}/rollback_slot.txt"

if [ ! -f "${ROLLBACK_SLOT_FILE}" ]; then
    echo "ERROR: Rollback slot record '${ROLLBACK_SLOT_FILE}' not found!" >&2
    exit 1
fi

ROLLBACK_SLOT="$(cat "${ROLLBACK_SLOT_FILE}" | tr -d ' \n')"
CURRENT_SLOT="$(cat "${ACTIVE_SLOT_FILE}" | tr -d ' \n')"

echo "=================================================================="
echo " WiFi Billing SaaS — Emergency Rollback Initiation"
echo " Rollback Mode: ${MODE}"
echo " Current Slot : ${CURRENT_SLOT}"
echo " Target Slot  : ${ROLLBACK_SLOT}"
echo "=================================================================="

if [ "${ROLLBACK_SLOT}" = "legacy" ]; then
    echo "=== Rolling back to Legacy Production Release ==="
    # Ensure legacy services are started if cold
    if ! sudo /usr/local/bin/deploy-service-control status legacy-backend >/dev/null 2>&1; then
        echo "Starting legacy-backend and legacy-frontend services (Cold Rollback)..."
        sudo /usr/local/bin/deploy-service-control start legacy-backend
        sudo /usr/local/bin/deploy-service-control start legacy-frontend
        
        # Verify readiness before returning traffic (measured: 18-28s)
        echo "Verifying legacy backend readiness on :8080..."
        LEGACY_READY=false
        for i in $(seq 1 60); do
            if curl -s "http://127.0.0.1:8080/api/health" | grep -q '"database":"CONNECTED"'; then
                LEGACY_READY=true
                echo " Legacy backend is ready! (attempt ${i})"
                break
            fi
            sleep 1
        done
        if [ "${LEGACY_READY}" != "true" ]; then
            echo "ERROR: Legacy backend failed readiness check!" >&2
            exit 1
        fi
    fi

    # Switch Nginx to legacy upstream (slot blue pointing to 8080/3000)
    sudo /usr/local/bin/deploy-nginx-control switch-slot blue
    echo "legacy" > "${ACTIVE_SLOT_FILE}"
    echo "${CURRENT_SLOT}" > "${ROLLBACK_SLOT_FILE}"
    echo "Rollback to legacy completed successfully."
    exit 0
fi

# Standard Blue/Green Rollback
if [ "${MODE}" = "--cold" ] || [ "${MODE}" = "cold" ]; then
    echo "=== Executing COLD ROLLBACK: Starting standby slot ${ROLLBACK_SLOT} first ==="
    sudo /usr/local/bin/deploy-service-control start "backend-${ROLLBACK_SLOT}"
    sudo /usr/local/bin/deploy-service-control start "frontend-${ROLLBACK_SLOT}"
    
    # Verify readiness (measured: 18-28s)
    PORT=8080
    [ "${ROLLBACK_SLOT}" = "green" ] && PORT=8081
    echo "Verifying readiness on port ${PORT}..."
    READY=false
    for i in $(seq 1 60); do
        if curl -s "http://127.0.0.1:${PORT}/api/health" | grep -q '"database":"CONNECTED"'; then
            READY=true
            echo " Target slot ${ROLLBACK_SLOT} is ready! (attempt ${i})"
            break
        fi
        sleep 1
    done
    if [ "${READY}" != "true" ]; then
        echo "ERROR: Rollback target failed health check!" >&2
        exit 1
    fi
fi

# Perform atomic upstream swap
START_TIME=$(date +%s%N)
sudo /usr/local/bin/deploy-nginx-control switch-slot "${ROLLBACK_SLOT}"
END_TIME=$(date +%s%N)
DURATION_MS=$(( (END_TIME - START_TIME) / 1000000 ))

echo "${ROLLBACK_SLOT}" > "${ACTIVE_SLOT_FILE}"
echo "${CURRENT_SLOT}" > "${ROLLBACK_SLOT_FILE}"

echo "=================================================================="
echo " Rollback to ${ROLLBACK_SLOT} completed in ${DURATION_MS} ms!"
echo " Live traffic is now served by ${ROLLBACK_SLOT}."
echo "=================================================================="
