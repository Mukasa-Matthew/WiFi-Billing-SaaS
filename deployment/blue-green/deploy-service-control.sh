#!/usr/bin/env bash
# ==============================================================================
# Restricted Root-Owned Helper for Deployment Service Management
# Location on server: /usr/local/bin/deploy-service-control (mode 755 root:root)
# Allowed commands: start, stop, restart, status for fixed units only
# ==============================================================================
set -euo pipefail

ACTION="${1:-}"
TARGET="${2:-}"

case "${TARGET}" in
    backend-blue)
        UNIT="wifi-backend-blue.service"
        ;;
    backend-green)
        UNIT="wifi-backend-green.service"
        ;;
    frontend-blue)
        UNIT="wifi-frontend-blue.service"
        ;;
    frontend-green)
        UNIT="wifi-frontend-green.service"
        ;;
    worker)
        UNIT="wifi-worker.service"
        ;;
    legacy-backend)
        UNIT="wifi-backend.service"
        ;;
    legacy-frontend)
        UNIT="wifi-frontend.service"
        ;;
    *)
        echo "ERROR: Invalid target '${TARGET}'." >&2
        exit 1
        ;;
esac

case "${ACTION}" in
    start|stop|restart|status)
        exec /bin/systemctl "${ACTION}" "${UNIT}"
        ;;
    *)
        echo "ERROR: Invalid action '${ACTION}'. Allowed: start, stop, restart, status." >&2
        exit 1
        ;;
esac
