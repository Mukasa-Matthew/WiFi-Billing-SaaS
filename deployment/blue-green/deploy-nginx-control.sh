#!/usr/bin/env bash
# ==============================================================================
# Restricted Root-Owned Helper for Nginx Upstream Switching
# Location on server: /usr/local/bin/deploy-nginx-control (mode 755 root:root)
# ==============================================================================
set -euo pipefail

ACTION="${1:-}"
TARGET_SLOT="${2:-}"
CONF_FILE="/etc/nginx/conf.d/upstream-active.conf"

case "${ACTION}" in
    switch-slot)
        case "${TARGET_SLOT}" in
            blue)
                cat << 'EOF_UPSTREAM' > "${CONF_FILE}"
# Active upstream definition — Managed by deploy-nginx-control
upstream active-frontend {
    server 127.0.0.1:3000;
    keepalive 32;
}

upstream active-backend {
    server 127.0.0.1:8080;
    keepalive 32;
}
EOF_UPSTREAM
                ;;
            green)
                cat << 'EOF_UPSTREAM' > "${CONF_FILE}"
# Active upstream definition — Managed by deploy-nginx-control
upstream active-frontend {
    server 127.0.0.1:3001;
    keepalive 32;
}

upstream active-backend {
    server 127.0.0.1:8081;
    keepalive 32;
}
EOF_UPSTREAM
                ;;
            *)
                echo "ERROR: Invalid slot '${TARGET_SLOT}'. Allowed: blue, green." >&2
                exit 1
                ;;
        esac

        # Validate syntax strictly before reloading
        if ! /usr/sbin/nginx -t; then
            echo "ERROR: Nginx syntax validation failed." >&2
            exit 1
        fi

        # Reload workers gracefully without dropping connections
        exec /bin/systemctl reload nginx
        ;;

    test)
        exec /usr/sbin/nginx -t
        ;;

    reload)
        if ! /usr/sbin/nginx -t; then
            echo "ERROR: Nginx syntax validation failed." >&2
            exit 1
        fi
        exec /bin/systemctl reload nginx
        ;;

    *)
        echo "ERROR: Invalid action '${ACTION}'. Allowed: switch-slot <blue|green>, test, reload." >&2
        exit 1
        ;;
esac
