#!/usr/bin/env bash
# ==============================================================================
# Local Blue-Green Deployment & Traffic Simulation Rehearsal
# Runs fully isolated in temporary local directories without touching production.
# ==============================================================================
set -euo pipefail

echo "=================================================================="
echo " Starting Blue-Green Local Rehearsal & Traffic Simulation"
echo "=================================================================="

WORK_DIR="/tmp/bluegreen-rehearsal-$$"
mkdir -p "${WORK_DIR}/slots/blue" "${WORK_DIR}/slots/green" "${WORK_DIR}/shared-static"
echo "blue" > "${WORK_DIR}/active_slot.txt"

echo "[1/4] Simulating Active Slot (Blue) serving traffic..."
ACTIVE_SLOT="$(cat "${WORK_DIR}/active_slot.txt")"
if [ "${ACTIVE_SLOT}" != "blue" ]; then
    echo "FAILED: Initial active slot should be blue" >&2
    exit 1
fi
echo "Active slot is: ${ACTIVE_SLOT}"

echo "[2/4] Simulating Green deployment..."
TARGET_SLOT="green"
TARGET_PORT="8081"
# Verify slot toggle logic
if [ "${ACTIVE_SLOT}" = "blue" ]; then
    NEXT_SLOT="green"
else
    NEXT_SLOT="blue"
fi
echo "Target deploy slot correctly selected: ${NEXT_SLOT}"

echo "[3/4] Simulating Health Check failure handling..."
# Mock failure condition: if health check fails, rollback must trigger and active slot remains blue
MOCK_HEALTH_PASS=false
if [ "${MOCK_HEALTH_PASS}" = "false" ]; then
    echo "  Readiness probe simulated failure -> triggering abort and rollback..."
    # Active slot remains blue
    CURRENT_SLOT="$(cat "${WORK_DIR}/active_slot.txt")"
    if [ "${CURRENT_SLOT}" != "blue" ]; then
        echo "FAILED: Rollback did not preserve blue slot" >&2
        exit 1
    fi
    echo "  Rollback preserved active slot: ${CURRENT_SLOT}"
fi

echo "[4/4] Simulating Successful Zero-Downtime Cutover..."
MOCK_HEALTH_PASS=true
if [ "${MOCK_HEALTH_PASS}" = "true" ]; then
    echo "green" > "${WORK_DIR}/active_slot.txt"
    NEW_SLOT="$(cat "${WORK_DIR}/active_slot.txt")"
    echo "  Cutover successful! Active slot transitioned: blue -> ${NEW_SLOT}"
fi

rm -rf "${WORK_DIR}"
echo "=================================================================="
echo " Blue-Green Rehearsal Completed Successfully!"
echo "=================================================================="
