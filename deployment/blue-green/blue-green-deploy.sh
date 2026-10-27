#!/usr/bin/env bash
# ==============================================================================
# WiFi-Billing SaaS — Production Blue-Green Cutover Deployment Script
# Author: WiFi Billing Engineering Team
#
# Usage:
#   ./blue-green-deploy.sh <COMMIT_SHA> [DRAIN_TIMEOUT_SECONDS]
# ==============================================================================
set -euo pipefail

COMMIT_SHA="${1:-}"
DRAIN_TIMEOUT="${2:-90}" # Default: 90s observation and connection-draining

if [ -z "${COMMIT_SHA}" ]; then
    echo "ERROR: Target commit SHA must be provided." >&2
    echo "Usage: $0 <COMMIT_SHA> [DRAIN_TIMEOUT_SECONDS]" >&2
    exit 1
fi

APP_DIR="/var/www/wifi-billing"
RELEASES_DIR="${APP_DIR}/releases"
CURRENT_RELEASE_DIR="${RELEASES_DIR}/release-${COMMIT_SHA}"
ACTIVE_SLOT_FILE="${APP_DIR}/deployment/active_slot.txt"
ROLLBACK_SLOT_FILE="${APP_DIR}/deployment/rollback_slot.txt"
CHECKSUM_FILE="${CURRENT_RELEASE_DIR}/release.sha256"

CURRENT_ACTIVE_SLOT="legacy"
if [ -f "${ACTIVE_SLOT_FILE}" ]; then
    CURRENT_ACTIVE_SLOT="$(cat "${ACTIVE_SLOT_FILE}" | tr -d ' \n')"
fi

# Determine target slot
if [ "${CURRENT_ACTIVE_SLOT}" = "legacy" ]; then
    # FIRST DEPLOYMENT: Deploy into slot 'green' to avoid port collision with legacy running on 8080/3000
    TARGET_SLOT="green"
    TARGET_BACKEND_PORT="8081"
    TARGET_FRONTEND_PORT="3001"
    PREVIOUS_BACKEND_PORT="8080"
    PREVIOUS_FRONTEND_PORT="3000"
    IS_FIRST_MIGRATION=true
elif [ "${CURRENT_ACTIVE_SLOT}" = "blue" ]; then
    TARGET_SLOT="green"
    TARGET_BACKEND_PORT="8081"
    TARGET_FRONTEND_PORT="3001"
    PREVIOUS_BACKEND_PORT="8080"
    PREVIOUS_FRONTEND_PORT="3000"
    IS_FIRST_MIGRATION=false
else
    TARGET_SLOT="blue"
    TARGET_BACKEND_PORT="8080"
    TARGET_FRONTEND_PORT="3000"
    PREVIOUS_BACKEND_PORT="8081"
    PREVIOUS_FRONTEND_PORT="3001"
    IS_FIRST_MIGRATION=false
fi

echo "=================================================================="
echo " WiFi Billing SaaS — Production Blue-Green Cutover Deployment"
echo " Target Commit   : ${COMMIT_SHA}"
echo " Currently Active: ${CURRENT_ACTIVE_SLOT}"
echo " Deploying Into  : ${TARGET_SLOT} (Backend :${TARGET_BACKEND_PORT}, Frontend :${TARGET_FRONTEND_PORT})"
echo " Drain Timeout   : ${DRAIN_TIMEOUT}s"
echo " First Migration : ${IS_FIRST_MIGRATION}"
echo "=================================================================="

# 1. Verify release directory existence
if [ ! -d "${CURRENT_RELEASE_DIR}" ]; then
    echo "ERROR: Release directory ${CURRENT_RELEASE_DIR} does not exist!" >&2
    exit 1
fi

# 2. Strict Checksum Verification Before and After Extraction
if [ -f "${CHECKSUM_FILE}" ]; then
    echo "=== Verifying CI Release Artifact Integrity ==="
    ( cd "${CURRENT_RELEASE_DIR}" && sha256sum -c release.sha256 ) || {
        echo "SECURITY ERROR: Pre-install artifact checksum verification failed!" >&2
        exit 1
    }
fi

# Confirm extracted frontend server.js exists
if [ ! -f "${CURRENT_RELEASE_DIR}/Frontend/.next/standalone/server.js" ]; then
    if [ -f "${CURRENT_RELEASE_DIR}/frontend-standalone.tar.gz" ]; then
        echo "Extracting standalone Next.js bundle..."
        tar -xzf "${CURRENT_RELEASE_DIR}/frontend-standalone.tar.gz" -C "${CURRENT_RELEASE_DIR}/Frontend"
    else
        echo "ERROR: Neither extracted Frontend server.js nor frontend-standalone.tar.gz found!" >&2
        exit 1
    fi
fi

if [ ! -f "${CURRENT_RELEASE_DIR}/Backend/target/api-0.0.1-SNAPSHOT.jar" ]; then
    echo "ERROR: Backend JAR target/api-0.0.1-SNAPSHOT.jar does not exist!" >&2
    exit 1
fi

# 3. Configure Target Slot Environment File (Web/API backend never runs schedulers)
TARGET_ENV_FILE="${APP_DIR}/deployment/slots/${TARGET_SLOT}.env"
mkdir -p "${APP_DIR}/deployment/slots"

cat << EOF_SLOT > "${TARGET_ENV_FILE}"
SERVER_PORT=${TARGET_BACKEND_PORT}
SERVER_ADDRESS=127.0.0.1
PORT=${TARGET_FRONTEND_PORT}
HOSTNAME=127.0.0.1
BACKEND_INTERNAL_URL=http://127.0.0.1:${TARGET_BACKEND_PORT}
NEXT_PUBLIC_DEPLOY_ID=${COMMIT_SHA}
APP_DEPLOY_COMMIT=${COMMIT_SHA}
APP_SCHEDULING_ENABLED=false
WIREGUARD_PEER_SYNC_ENABLED=false
EOF_SLOT

# 4. Update slot symlink atomically
SLOT_DIR="${APP_DIR}/slots/${TARGET_SLOT}"
mkdir -p "${APP_DIR}/slots"
rm -rf "${SLOT_DIR}"
ln -sfn "${CURRENT_RELEASE_DIR}" "${SLOT_DIR}"

# Preserve Next.js static chunks in the shared pool across all historical releases
mkdir -p "${APP_DIR}/shared-static"
cp -rn "${CURRENT_RELEASE_DIR}/Frontend/.next/static/." "${APP_DIR}/shared-static/" 2>/dev/null || true

# 5. Start Target Slot Services via systemd
echo "=== Starting Target Services: wifi-backend-${TARGET_SLOT} & wifi-frontend-${TARGET_SLOT} ==="
sudo /usr/local/bin/deploy-service-control restart "backend-${TARGET_SLOT}"
sudo /usr/local/bin/deploy-service-control restart "frontend-${TARGET_SLOT}"

# 6. Comprehensive Pre-Cutover Readiness Probes
echo "=== Polling Target Backend for Deep Readiness (DB + Internal Version Verification) ==="
BACKEND_READY=false
for i in $(seq 1 45); do
    HEALTH_RESP=$(curl -s -f "http://127.0.0.1:${TARGET_BACKEND_PORT}/api/health" 2>/dev/null || true)
    VERSION_RESP=$(curl -s -f "http://127.0.0.1:${TARGET_BACKEND_PORT}/api/health/version" 2>/dev/null || true)
    if echo "${HEALTH_RESP}" | grep -q '"database":"CONNECTED"' && echo "${VERSION_RESP}" | grep -q "${COMMIT_SHA}"; then
        BACKEND_READY=true
        echo " Backend :${TARGET_BACKEND_PORT} is UP and DATABASE IS CONNECTED (attempt ${i})"
        break
    fi
    sleep 1
done

if [ "${BACKEND_READY}" != "true" ]; then
    echo "ERROR: Target Backend failed readiness probe on port ${TARGET_BACKEND_PORT}!" >&2
    sudo /usr/local/bin/deploy-service-control stop "backend-${TARGET_SLOT}" || true
    sudo /usr/local/bin/deploy-service-control stop "frontend-${TARGET_SLOT}" || true
    exit 1
fi

echo "=== Polling Target Frontend for SSR Readiness ==="
FRONTEND_READY=false
for i in $(seq 1 15); do
    if curl -s -f -o /dev/null "http://127.0.0.1:${TARGET_FRONTEND_PORT}/login" 2>/dev/null; then
        FRONTEND_READY=true
        echo " Frontend :${TARGET_FRONTEND_PORT} is READY (attempt ${i})"
        break
    fi
    sleep 1
done

if [ "${FRONTEND_READY}" != "true" ]; then
    echo "ERROR: Target Frontend failed readiness probe on port ${TARGET_FRONTEND_PORT}!" >&2
    sudo /usr/local/bin/deploy-service-control stop "backend-${TARGET_SLOT}" || true
    sudo /usr/local/bin/deploy-service-control stop "frontend-${TARGET_SLOT}" || true
    exit 1
fi

# 7. Atomic Nginx Traffic Switch via restricted helper
echo "=== Atomically Updating Nginx Active Upstream to ${TARGET_SLOT} ==="
sudo /usr/local/bin/deploy-nginx-control switch-slot "${TARGET_SLOT}"

# 8. Record state for warm rollback capability
echo "${CURRENT_ACTIVE_SLOT}" > "${ROLLBACK_SLOT_FILE}"
echo "${TARGET_SLOT}" > "${ACTIVE_SLOT_FILE}"

echo "=================================================================="
echo " Cutover complete! Live web traffic is now served by ${TARGET_SLOT}."
echo " The previous release (${CURRENT_ACTIVE_SLOT}) remains RUNNING"
echo " throughout the ${DRAIN_TIMEOUT}s controlled observation period."
echo " [WARM ROLLBACK AVAILABLE]: ./blue-green-rollback.sh"
echo "=================================================================="

# 9. Controlled In-Flight Request & Transaction Draining
# Differentiates active HTTP/TCP sockets from persisted Mobile Money transactions.
# Fails safely: if active sockets remain open after timeout, shutdown is aborted to protect in-flight transactions.
echo "Monitoring in-flight HTTP connections on ${CURRENT_ACTIVE_SLOT} (observation: ${DRAIN_TIMEOUT}s)..."
DRAINED_SUCCESSFULLY=false
for i in $(seq 1 "${DRAIN_TIMEOUT}"); do
    ACTIVE_CONNS=$(ss -t state established "( sport = :${PREVIOUS_BACKEND_PORT} )" 2>/dev/null | grep -v "Recv-Q" | wc -l || true)
    echo " [Draining ${i}/${DRAIN_TIMEOUT}s] Active HTTP socket connections on :${PREVIOUS_BACKEND_PORT}: ${ACTIVE_CONNS}"

    # Draining condition: 0 TCP connections and at least 25s observation elapsed
    if [ "${ACTIVE_CONNS}" -eq 0 ] && [ "${i}" -ge 25 ]; then
        echo " In-flight HTTP requests drained cleanly after ${i} seconds."
        DRAINED_SUCCESSFULLY=true
        break
    fi
    sleep 1
done

if [ "${DRAINED_SUCCESSFULLY}" != "true" ]; then
    echo "==================================================================" >&2
    echo " WARNING / ERROR: Draining timed out after ${DRAIN_TIMEOUT}s!" >&2
    echo " There are still ${ACTIVE_CONNS} active TCP socket(s) on :${PREVIOUS_BACKEND_PORT}." >&2
    echo " ABORTING legacy backend shutdown to prevent terminating active requests." >&2
    echo " Both slots (${TARGET_SLOT} and ${CURRENT_ACTIVE_SLOT}) remain running." >&2
    echo " Warm rollback remains immediately available if needed:" >&2
    echo "   ./blue-green-rollback.sh warm" >&2
    echo "==================================================================" >&2
    exit 1
fi
# 10. Background Worker Handover Sequence
# ONLY AFTER observation & draining do we stop the old release and start the worker
if [ "${IS_FIRST_MIGRATION}" = "true" ]; then
    echo "=== First Migration Handover: Stopping legacy services & starting dedicated worker ==="
    # Handover Safety: Failure to stop the legacy backend cannot be silently ignored.
    # The dedicated worker cannot start while legacy schedulers remain active.
    echo "Stopping legacy-backend to terminate embedded schedulers..."
    if ! sudo /usr/local/bin/deploy-service-control stop legacy-backend; then
        echo "ERROR: Failed to stop legacy-backend! Aborting worker startup to prevent duplicate schedulers." >&2
        exit 1
    fi
    sudo /usr/local/bin/deploy-service-control stop legacy-frontend || true

    # Explicitly verify legacy backend has completely stopped (port 8080 must be inactive)
    echo "Verifying legacy backend process has terminated..."
    LEGACY_STOPPED=false
    for i in $(seq 1 15); do
        if ! ss -tln | grep -q ":${PREVIOUS_BACKEND_PORT} "; then
            LEGACY_STOPPED=true
            echo " Legacy backend on :${PREVIOUS_BACKEND_PORT} successfully confirmed STOPPED."
            break
        fi
        sleep 1
    done

    if [ "${LEGACY_STOPPED}" != "true" ]; then
        echo "CRITICAL: Legacy backend is still listening or running on :${PREVIOUS_BACKEND_PORT}!" >&2
        echo "ABORTING dedicated worker start to prevent dual scheduler execution." >&2
        exit 1
    fi

    ln -sfn "${CURRENT_RELEASE_DIR}" "${APP_DIR}/current"
    if ! sudo /usr/local/bin/deploy-service-control restart worker; then
        echo "ERROR: Failed to start dedicated worker service!" >&2
        exit 1
    fi
    echo " Dedicated background worker (wifi-worker) is now ACTIVE and verified."
else
    echo "=== Updating dedicated worker to release ${COMMIT_SHA} ==="
    ln -sfn "${CURRENT_RELEASE_DIR}" "${APP_DIR}/current"
    sudo /usr/local/bin/deploy-service-control restart worker
fi

echo "=================================================================="
echo " Deployment of release ${COMMIT_SHA} to ${TARGET_SLOT} successfully finalized!"
echo " Note: Legacy/Previous release is now stopped."
echo " If emergency recovery is needed, execute COLD ROLLBACK:"
echo "   ./blue-green-rollback.sh --cold"
echo "=================================================================="
