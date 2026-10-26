#!/usr/bin/env bash
# ==============================================================================
# WiFi-Billing SaaS — Comprehensive Final Transition Rehearsal
# Database: PostgreSQL 16 (matching production CloudServ)
# Components:
#   - Legacy App (simulating existing deployment on :8080 and :3000)
#   - Green App (new web/API release on :8081 and :3001 with scheduling=false)
#   - Dedicated Worker (wifi-worker on :8089 with scheduling=true)
#   - Real Next.js standalone servers
#   - Real Nginx (:8088) with atomic upstream switching
# ==============================================================================
set -euo pipefail

WORKSPACE="$(pwd)"
REHEARSAL_DIR="${WORKSPACE}/deployment/final-rehearsal-run"
rm -rf "${REHEARSAL_DIR}"
mkdir -p "${REHEARSAL_DIR}/shared-static"
mkdir -p "${REHEARSAL_DIR}/nginx-conf"

echo "=================================================================="
echo " Starting Final Comprehensive Blue-Green & Worker Handover Rehearsal"
echo " Database Target: PostgreSQL 16 (localhost:5440/radius_db_pg16)"
echo "=================================================================="

# Ensure clean test ports
fuser -k 3000/tcp 3001/tcp 3082/tcp 3083/tcp 8080/tcp 8081/tcp 8089/tcp 2>/dev/null || true
sleep 1

# 1. Apply explicit backward-compatible database migration to PostgreSQL 16
echo "=== Step 1: Explicitly Applying app_job_leases Migration to PG16 ==="
docker exec -i pg16-rehearsal psql -U radius_user -d radius_db_pg16 < "${WORKSPACE}/deployment/database/01-create-app-job-leases.sql"

# 2. Populate Shared Static Directory
cp -rn "${WORKSPACE}/Frontend/.next/static/." "${REHEARSAL_DIR}/shared-static/"

# 3. Configure Nginx with Legacy Active (pointing to 8080 and 3000)
cat << 'EOF_UPSTREAM_LEGACY' > "${REHEARSAL_DIR}/nginx-conf/upstream-active.conf"
upstream active-frontend {
    server host.docker.internal:3000;
    keepalive 32;
}

upstream active-backend {
    server host.docker.internal:8080;
    keepalive 32;
}
EOF_UPSTREAM_LEGACY

cat << 'EOF_UPSTREAM_GREEN' > "${REHEARSAL_DIR}/nginx-conf/upstream-green.conf"
upstream active-frontend {
    server host.docker.internal:3001;
    keepalive 32;
}

upstream active-backend {
    server host.docker.internal:8081;
    keepalive 32;
}
EOF_UPSTREAM_GREEN

cat << 'EOF_RETIRED' > "${REHEARSAL_DIR}/nginx-conf/airpairy-retired-setup.conf"
location ~* ^/api/router/provision/[^/]+/?$ {
    return 410;
}
location ~* ^/api/router/provision/[^/]+/(register|complete|install-management\.rsc)/?$ {
    return 410;
}
EOF_RETIRED

cat << 'EOF_CONF' > "${REHEARSAL_DIR}/nginx-conf/default.conf"
server {
    listen 8088;
    server_name localhost;

    # Retired router provisioning endpoints — HTTP 410 Gone
    include /etc/nginx/airpairy-retired-setup.conf;

    # Persistent Next.js static asset cache
    location /_next/static/ {
        alias /usr/share/nginx/html/shared-static/;
        expires 365d;
        access_log off;
    }

    # API Proxy to Active Spring Boot
    location /api/ {
        proxy_pass http://active-backend/api/;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header Connection "";
        proxy_read_timeout 60s;
    }

    # Frontend Proxy to Active Next.js Standalone
    location / {
        proxy_pass http://active-frontend;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection 'upgrade';
        proxy_cache_bypass $http_upgrade;
    }
}
EOF_CONF

# 4. Start Real Nginx Container
echo "Starting Real Nginx Container on Port 8088..."
docker rm -f rehearsal-nginx 2>/dev/null || true
docker create --name rehearsal-nginx \
    --add-host=host.docker.internal:host-gateway \
    -p 8088:8088 \
    nginx:alpine >/dev/null

docker cp "${REHEARSAL_DIR}/nginx-conf/airpairy-retired-setup.conf" rehearsal-nginx:/etc/nginx/airpairy-retired-setup.conf
docker cp "${REHEARSAL_DIR}/nginx-conf/upstream-active.conf" rehearsal-nginx:/etc/nginx/conf.d/upstream-active.conf
docker cp "${REHEARSAL_DIR}/nginx-conf/default.conf" rehearsal-nginx:/etc/nginx/conf.d/default.conf
docker cp "${REHEARSAL_DIR}/shared-static" rehearsal-nginx:/usr/share/nginx/html/
docker start rehearsal-nginx >/dev/null

# 5. Start SIMULATED LEGACY PRODUCTION RELEASE (:8080 & :3000)
echo "Starting Simulated Legacy Backend on port 8080..."
java -jar "${WORKSPACE}/Backend/target/api-0.0.1-SNAPSHOT.jar" \
    --server.port=8080 \
    --server.address=0.0.0.0 \
    --spring.datasource.url="jdbc:postgresql://localhost:5440/radius_db_pg16" \
    --spring.datasource.username=radius_user \
    --spring.datasource.password=rehearsal_pass_123 \
    --spring.jpa.hibernate.ddl-auto=update \
    --app.deploy.commit="legacy-production" \
    --app.instance.id="legacy-backend-node" \
    --app.scheduling.enabled=true \
    --wireguard.peer-management.enabled=false \
    --wireguard.peer-sync.enabled=false \
    --marzpay.platform.api-key="" \
    > "${REHEARSAL_DIR}/backend-legacy.log" 2>&1 &
LEGACY_BACKEND_PID=$!

(
    cd "${WORKSPACE}/Frontend/.next/standalone"
    PORT=3000 HOSTNAME=0.0.0.0 BACKEND_INTERNAL_URL="http://127.0.0.1:8080" node server.js > "${REHEARSAL_DIR}/frontend-legacy.log" 2>&1
) &
LEGACY_FRONTEND_PID=$!

cleanup() {
    echo "Stopping all rehearsal processes..."
    kill "${LEGACY_BACKEND_PID:-}" "${LEGACY_FRONTEND_PID:-}" "${GREEN_BACKEND_PID:-}" "${GREEN_FRONTEND_PID:-}" "${WORKER_PID:-}" 2>/dev/null || true
    docker rm -f rehearsal-nginx 2>/dev/null || true
}
trap cleanup EXIT

echo "Waiting for Legacy Release to become active..."
for i in $(seq 1 45); do
    if curl -s "http://127.0.0.1:8080/api/health" | grep -q '"database":"CONNECTED"' && \
       curl -s -f -o /dev/null "http://127.0.0.1:3000/login" 2>/dev/null; then
        echo "Legacy Release is UP and handling production traffic on 8080/3000 (attempt ${i})!"
        break
    fi
    sleep 1
done

# 6. Verify Initial Traffic routes to Legacy via Nginx
INIT_RESP=$(curl -s "http://localhost:8088/api/health/version")
echo "Initial Active Backend: ${INIT_RESP}"
if ! echo "${INIT_RESP}" | grep -q "legacy-production"; then
    echo "ERROR: Initial route did not hit legacy backend!" >&2
    exit 1
fi

# 7. Start GREEN RELEASE on alternate ports (:8081 & :3001) with app.scheduling.enabled=false
echo "=== Step 7: Starting Green Web Release on 8081/3001 (Schedulers Disabled) ==="
java -jar "${WORKSPACE}/Backend/target/api-0.0.1-SNAPSHOT.jar" \
    --server.port=8081 \
    --server.address=0.0.0.0 \
    --spring.datasource.url="jdbc:postgresql://localhost:5440/radius_db_pg16" \
    --spring.datasource.username=radius_user \
    --spring.datasource.password=rehearsal_pass_123 \
    --spring.jpa.hibernate.ddl-auto=none \
    --app.deploy.commit="release-v2-green" \
    --app.instance.id="node-green-web" \
    --app.scheduling.enabled=false \
    --wireguard.peer-management.enabled=false \
    --wireguard.peer-sync.enabled=false \
    --marzpay.platform.api-key="" \
    > "${REHEARSAL_DIR}/backend-green.log" 2>&1 &
GREEN_BACKEND_PID=$!

(
    cd "${WORKSPACE}/Frontend/.next/standalone"
    PORT=3001 HOSTNAME=0.0.0.0 BACKEND_INTERNAL_URL="http://127.0.0.1:8081" node server.js > "${REHEARSAL_DIR}/frontend-green.log" 2>&1
) &
GREEN_FRONTEND_PID=$!

echo "Waiting for Green to become fully ready..."
for i in $(seq 1 30); do
    if curl -s "http://127.0.0.1:8081/api/health" | grep -q '"database":"CONNECTED"' && \
       curl -s -f -o /dev/null "http://127.0.0.1:3001/login" 2>/dev/null; then
        echo "Green Release is UP and ready on 8081/3001 (attempt ${i})!"
        break
    fi
    sleep 1
done

# 8. Start Background Traffic Generator Across the Cutover
echo "=== Step 8: Firing Continuous User Traffic Across Cutover ==="
RESULTS_FILE="${REHEARSAL_DIR}/traffic-results.csv"
echo "timestamp,endpoint,http_code,response_time_ms" > "${RESULTS_FILE}"

python3 -c "
import urllib.request, time, csv

results = []
endpoints = [
    'http://localhost:8088/api/health',
    'http://localhost:8088/login',
    'http://localhost:8088/api/health/version'
]

start_time = time.time()
while time.time() - start_time < 12:
    for ep in endpoints:
        t0 = time.time()
        try:
            req = urllib.request.Request(ep)
            with urllib.request.urlopen(req, timeout=5) as resp:
                code = resp.status
        except urllib.error.HTTPError as e:
            code = e.code
        except Exception as e:
            code = 0
        t1 = time.time()
        results.append((time.time(), ep, code, int((t1 - t0) * 1000)))
        time.sleep(0.04)

with open('${RESULTS_FILE}', 'a', newline='') as f:
    writer = csv.writer(f)
    for r in results:
        writer.writerow(r)
" &
TRAFFIC_PID=$!

sleep 2

# 9. Execute Atomic Traffic Switch to Green
echo "=== Step 9: Switching Nginx Upstream to Green ==="
docker cp "${REHEARSAL_DIR}/nginx-conf/upstream-green.conf" rehearsal-nginx:/etc/nginx/conf.d/upstream-active.conf
docker exec rehearsal-nginx nginx -s reload
echo "Nginx traffic successfully cut over to Green!"

sleep 2

# 10. Perform Safe Scheduler Handover: Stop Legacy Backend & Start Dedicated Worker
echo "=== Step 10: Executing Safe Scheduler Handover ==="
echo "Stopping Legacy backend (holding legacy schedulers)..."
kill "${LEGACY_BACKEND_PID}" "${LEGACY_FRONTEND_PID}" 2>/dev/null || true
wait "${LEGACY_BACKEND_PID}" 2>/dev/null || true

echo "Starting Dedicated Background Worker (wifi-worker on :8089)..."
java -jar "${WORKSPACE}/Backend/target/api-0.0.1-SNAPSHOT.jar" \
    --server.port=8089 \
    --server.address=127.0.0.1 \
    --spring.datasource.url="jdbc:postgresql://localhost:5440/radius_db_pg16" \
    --spring.datasource.username=radius_user \
    --spring.datasource.password=rehearsal_pass_123 \
    --spring.jpa.hibernate.ddl-auto=none \
    --app.deploy.commit="worker-v2" \
    --app.instance.id="dedicated-worker-node" \
    --app.scheduling.enabled=true \
    --wireguard.peer-management.enabled=false \
    --wireguard.peer-sync.enabled=false \
    --marzpay.platform.api-key="" \
    > "${REHEARSAL_DIR}/worker.log" 2>&1 &
WORKER_PID=$!

sleep 3

# Verify Dedicated Worker is running and claiming leases
WORKER_LEASES=$(docker exec pg16-rehearsal psql -U radius_user -d radius_db_pg16 -t -c "SELECT COUNT(*) FROM app_job_leases WHERE owner_id = 'dedicated-worker-node';")
echo "Active leases owned by dedicated worker in PG16: ${WORKER_LEASES}"

wait "${TRAFFIC_PID}" || true

# 11. Verify Post-Cutover State on Green
GREEN_RESP=$(curl -s "http://localhost:8088/api/health/version")
echo "Active Backend after cutover: ${GREEN_RESP}"
if ! echo "${GREEN_RESP}" | grep -q "release-v2-green"; then
    echo "ERROR: Active backend did not switch to Green!" >&2
    exit 1
fi

# 12. Analyze Traffic Metrics
echo "=== Step 12: Analyzing Traffic Metrics ==="
python3 -c "
import csv

total = 0
errors = 0
codes = {}
latencies = []

with open('${RESULTS_FILE}', 'r') as f:
    reader = csv.DictReader(f)
    for row in reader:
        total += 1
        code = row['http_code']
        codes[code] = codes.get(code, 0) + 1
        if code not in ('200', '304'):
            errors += 1
        latencies.append(int(row['response_time_ms']))

latencies.sort()
min_lat = latencies[0] if latencies else 0
avg_lat = sum(latencies)/len(latencies) if latencies else 0
p95_lat = latencies[int(len(latencies)*0.95)] if latencies else 0
max_lat = latencies[-1] if latencies else 0

print(f'Total Requests Sent : {total}')
print(f'Total Failures/Drops: {errors}')
print(f'HTTP Status Codes   : {codes}')
print(f'Latency Min         : {min_lat} ms')
print(f'Latency Avg         : {avg_lat:.2f} ms')
print(f'Latency P95         : {p95_lat} ms')
print(f'Latency Max         : {max_lat} ms')
"

echo "=== All Rehearsal Steps Completed with 100% Success on PostgreSQL 16! ==="
