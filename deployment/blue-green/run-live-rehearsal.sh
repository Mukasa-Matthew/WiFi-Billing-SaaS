#!/usr/bin/env bash
# ==============================================================================
# WiFi-Billing SaaS — Real Isolated Blue-Green Rehearsal with Real Next.js & Spring Boot
# ==============================================================================
set -euo pipefail

WORKSPACE="$(pwd)"
REHEARSAL_DIR="${WORKSPACE}/deployment/rehearsal-run"
rm -rf "${REHEARSAL_DIR}"
mkdir -p "${REHEARSAL_DIR}/shared-static"
mkdir -p "${REHEARSAL_DIR}/nginx-conf"

echo "=================================================================="
echo " Starting Real Isolated Blue-Green Rehearsal"
echo " Components: Real Spring Boot (:8082, :8083) + Real Next.js Standalone (:3082, :3083)"
echo " Database: Isolated PostgreSQL (localhost:5439/radius_db_rehearsal)"
echo " Proxy: Real Nginx (Port 8088)"
echo "=================================================================="

# Ensure clean test ports
fuser -k 3082/tcp 3083/tcp 8082/tcp 8083/tcp 2>/dev/null || true
sleep 1

# 1. Populate Shared Static Directory from real Next.js build
cp -rn "${WORKSPACE}/Frontend/.next/static/." "${REHEARSAL_DIR}/shared-static/"

# 2. Configure Nginx with hyphenated upstreams and standard include paths
cat << 'EOF_UPSTREAM_BLUE' > "${REHEARSAL_DIR}/nginx-conf/upstream-blue.conf"
upstream active-frontend {
    server host.docker.internal:3082;
    keepalive 32;
}

upstream active-backend {
    server host.docker.internal:8082;
    keepalive 32;
}
EOF_UPSTREAM_BLUE

cat << 'EOF_UPSTREAM_GREEN' > "${REHEARSAL_DIR}/nginx-conf/upstream-green.conf"
upstream active-frontend {
    server host.docker.internal:3083;
    keepalive 32;
}

upstream active-backend {
    server host.docker.internal:8083;
    keepalive 32;
}
EOF_UPSTREAM_GREEN

cp "${REHEARSAL_DIR}/nginx-conf/upstream-blue.conf" "${REHEARSAL_DIR}/nginx-conf/upstream-active.conf"

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

# 3. Start Real Nginx Container
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

# 4. Start REAL Next.js Standalone Frontends for Blue (:3082) and Green (:3083)
echo "Starting Real Next.js Standalone (Blue on :3082)..."
(
    cd "${WORKSPACE}/Frontend/.next/standalone"
    PORT=3082 HOSTNAME=0.0.0.0 BACKEND_INTERNAL_URL="http://127.0.0.1:8082" node server.js > "${REHEARSAL_DIR}/frontend-blue.log" 2>&1
) &
FRONTEND_BLUE_PID=$!

echo "Starting Real Next.js Standalone (Green on :3083)..."
(
    cd "${WORKSPACE}/Frontend/.next/standalone"
    PORT=3083 HOSTNAME=0.0.0.0 BACKEND_INTERNAL_URL="http://127.0.0.1:8083" node server.js > "${REHEARSAL_DIR}/frontend-green.log" 2>&1
) &
FRONTEND_GREEN_PID=$!

# 5. Start REAL Spring Boot Backends Blue (:8082) and Green (:8083) against radius_db_rehearsal
echo "Starting Spring Boot Blue on port 8082..."
java -jar "${WORKSPACE}/Backend/target/api-0.0.1-SNAPSHOT.jar" \
    --server.port=8082 \
    --server.address=0.0.0.0 \
    --spring.datasource.url="jdbc:postgresql://localhost:5439/radius_db_rehearsal" \
    --spring.datasource.username=radius_user \
    --spring.datasource.password=rehearsal_pass_123 \
    --spring.jpa.hibernate.ddl-auto=update \
    --app.deploy.commit="commit-blue-v1" \
    --app.instance.id="node-blue" \
    --wireguard.peer-management.enabled=false \
    --wireguard.peer-sync.enabled=false \
    --marzpay.platform.api-key="" \
    > "${REHEARSAL_DIR}/backend-blue.log" 2>&1 &
BACKEND_BLUE_PID=$!

cleanup() {
    echo "Stopping rehearsal processes..."
    kill "${FRONTEND_BLUE_PID}" "${FRONTEND_GREEN_PID}" "${BACKEND_BLUE_PID}" "${BACKEND_GREEN_PID:-}" 2>/dev/null || true
    docker rm -f rehearsal-nginx 2>/dev/null || true
}
trap cleanup EXIT

echo "Waiting for Spring Boot Blue and Next.js Blue to become ready..."
for i in $(seq 1 45); do
    if curl -s "http://127.0.0.1:8082/api/health" | grep -q '"database":"CONNECTED"' && \
       curl -s -f -o /dev/null "http://127.0.0.1:3082/login" 2>/dev/null; then
        echo "Blue Pair (Backend :8082 & Frontend :3082) is FULLY READY (attempt ${i})!"
        break
    fi
    sleep 1
done

echo "Starting Spring Boot Green on port 8083..."
java -jar "${WORKSPACE}/Backend/target/api-0.0.1-SNAPSHOT.jar" \
    --server.port=8083 \
    --server.address=0.0.0.0 \
    --spring.datasource.url="jdbc:postgresql://localhost:5439/radius_db_rehearsal" \
    --spring.datasource.username=radius_user \
    --spring.datasource.password=rehearsal_pass_123 \
    --spring.jpa.hibernate.ddl-auto=none \
    --app.deploy.commit="commit-green-v2" \
    --app.instance.id="node-green" \
    --wireguard.peer-management.enabled=false \
    --wireguard.peer-sync.enabled=false \
    --marzpay.platform.api-key="" \
    > "${REHEARSAL_DIR}/backend-green.log" 2>&1 &
BACKEND_GREEN_PID=$!

echo "Waiting for Spring Boot Green and Next.js Green to become ready..."
for i in $(seq 1 30); do
    if curl -s "http://127.0.0.1:8083/api/health" | grep -q '"database":"CONNECTED"' && \
       curl -s -f -o /dev/null "http://127.0.0.1:3083/login" 2>/dev/null; then
        echo "Green Pair (Backend :8083 & Frontend :3083) is FULLY READY (attempt ${i})!"
        break
    fi
    sleep 1
done

# 6. Verify Initial State Through Nginx
echo "=== Step 6: Verifying Initial Blue Route via Nginx (Port 8088) ==="
HEALTH_INITIAL=$(curl -s "http://localhost:8088/api/health/version")
echo "Initial Backend Version: ${HEALTH_INITIAL}"
if ! echo "${HEALTH_INITIAL}" | grep -q "commit-blue-v1"; then
    echo "ERROR: Expected commit-blue-v1 from Nginx route!" >&2
    exit 1
fi

LOGIN_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:8088/login")
echo "Next.js /login Route Status: ${LOGIN_STATUS} (Expected 200)"
if [ "${LOGIN_STATUS}" != "200" ]; then
    echo "ERROR: Next.js frontend route failed!" >&2
    exit 1
fi

RETIRED_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:8088/api/router/provision/test-portal-id")
echo "Retired Provisioning Endpoint Status: ${RETIRED_STATUS} (Expected 410)"
if [ "${RETIRED_STATUS}" != "410" ]; then
    echo "ERROR: Retired endpoint did not return 410 Gone!" >&2
    exit 1
fi

# 7. Execute Traffic Generation During Switch
echo "=== Step 7: Starting Continuous Traffic Across Frontend & Backend ==="
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
while time.time() - start_time < 10:
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

# 8. Perform Atomic Cutover from Blue to Green
echo "=== Step 8: Executing Atomic Cutover to Green in Nginx ==="
docker cp "${REHEARSAL_DIR}/nginx-conf/upstream-green.conf" rehearsal-nginx:/etc/nginx/conf.d/upstream-active.conf
docker exec rehearsal-nginx nginx -s reload
echo "Nginx reloaded with Green upstream."

sleep 3

# Verify Green active through Nginx
HEALTH_GREEN=$(curl -s "http://localhost:8088/api/health/version")
echo "Post-Cutover Backend Version: ${HEALTH_GREEN}"
if ! echo "${HEALTH_GREEN}" | grep -q "commit-green-v2"; then
    echo "ERROR: Expected commit-green-v2 from Nginx route after cutover!" >&2
    exit 1
fi

# 9. Test Emergency Rollback back to Blue
echo "=== Step 9: Testing Instant Rollback to Blue ==="
ROLLBACK_START=$(date +%s%N)
docker cp "${REHEARSAL_DIR}/nginx-conf/upstream-blue.conf" rehearsal-nginx:/etc/nginx/conf.d/upstream-active.conf
docker exec rehearsal-nginx nginx -s reload
ROLLBACK_END=$(date +%s%N)
ROLLBACK_MS=$(( (ROLLBACK_END - ROLLBACK_START) / 1000000 ))
echo "Rollback reload executed in ${ROLLBACK_MS} ms."

sleep 1

# Verify Blue active again
HEALTH_ROLLBACK=$(curl -s "http://localhost:8088/api/health/version")
echo "Post-Rollback Backend Version: ${HEALTH_ROLLBACK}"
if ! echo "${HEALTH_ROLLBACK}" | grep -q "commit-blue-v1"; then
    echo "ERROR: Expected commit-blue-v1 from Nginx route after rollback!" >&2
    exit 1
fi

wait "${TRAFFIC_PID}" || true

# 10. Analyze Rehearsal Metrics
echo "=== Step 10: Analyzing Traffic Metrics ==="
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
print(f'Rollback Time       : ${ROLLBACK_MS} ms')
"

echo "=== Real Next.js & Spring Boot Blue-Green Rehearsal Completed Successfully! ==="
