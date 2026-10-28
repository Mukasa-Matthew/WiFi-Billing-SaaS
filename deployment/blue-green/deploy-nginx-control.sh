#!/usr/bin/env bash
# ==============================================================================
# Restricted Root-Owned Helper for Nginx Upstream Switching & Process Signaling
# Location on server: /usr/local/bin/deploy-nginx-control (mode 755 root:root)
# ==============================================================================
set -euo pipefail

ACTION="${1:-}"
TARGET_SLOT="${2:-}"
CONF_FILE="/etc/nginx/conf.d/upstream-active.conf"

discover_host_nginx_master() {
    local candidate_pids=()
    while read -r pid; do
        [ -z "${pid}" ] && continue
        # Verify process exists and is actually nginx master
        if [ -d "/proc/${pid}" ]; then
            local cmdline
            cmdline=$(tr '\0' ' ' < "/proc/${pid}/cmdline" 2>/dev/null || true)
            # Ensure it is an nginx master process running on the host (not a worker)
            if echo "${cmdline}" | grep -q "nginx: master process"; then
                # Exclude containerized processes by checking cgroup
                local cgroup
                cgroup=$(cat "/proc/${pid}/cgroup" 2>/dev/null || true)
                if ! echo "${cgroup}" | grep -q -E 'docker|containerd'; then
                    candidate_pids+=("${pid}")
                fi
            fi
        fi
    done < <(pgrep -f "nginx: master process" || true)

    if [ "${#candidate_pids[@]}" -eq 1 ]; then
        echo "${candidate_pids[0]}"
        return 0
    elif [ "${#candidate_pids[@]}" -eq 0 ]; then
        echo "ERROR: No host Nginx master process detected." >&2
        return 1
    else
        echo "ERROR: Ambiguous Nginx master processes detected on host: ${candidate_pids[*]}" >&2
        return 1
    fi
}

reload_host_nginx() {
    # 1. Strictly test candidate syntax before signaling
    if ! /usr/sbin/nginx -t; then
        echo "ERROR: Nginx syntax validation failed. Aborting reload." >&2
        exit 1
    fi

    # 2. Prefer systemctl reload if unit is active and functional
    if systemctl is-active --quiet nginx.service 2>/dev/null; then
        if systemctl reload nginx.service 2>/dev/null; then
            echo "Nginx reloaded successfully via systemd."
            return 0
        fi
    fi

    # 3. Dynamic Host Process Signaling (Fallback when unit is inactive/desynchronized)
    local master_pid
    master_pid=$(discover_host_nginx_master)
    if [ -n "${master_pid}" ]; then
        echo "Signaling verified host Nginx master process (PID: ${master_pid}) with SIGHUP..."
        kill -HUP "${master_pid}"
        # Keep PID file in sync for standard tools
        echo "${master_pid}" > /run/nginx.pid 2>/dev/null || true
        echo "Nginx master (PID: ${master_pid}) signaled successfully."
        return 0
    else
        echo "ERROR: Failed to identify authoritative host Nginx master." >&2
        exit 1
    fi
}

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

        reload_host_nginx
        ;;

    test)
        exec /usr/sbin/nginx -t
        ;;

    reload)
        reload_host_nginx
        ;;

    discover-master)
        discover_host_nginx_master
        ;;

    *)
        echo "ERROR: Invalid action '${ACTION}'. Allowed: switch-slot <blue|green>, test, reload, discover-master." >&2
        exit 1
        ;;
esac
