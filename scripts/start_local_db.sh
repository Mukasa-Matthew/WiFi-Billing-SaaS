#!/usr/bin/env bash
# ==============================================================================
# Start / Initialize Isolated Local Development PostgreSQL Instance (Port 5435)
# Runs 100% unprivileged as the current user under .pgdata/ without touching
# system PostgreSQL clusters or cloudserv production databases.
# ==============================================================================
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PGDATA="$PROJECT_ROOT/.pgdata"
PGPORT="${LOCAL_DB_PORT:-5435}"
PGUSER="${LOCAL_DB_USER:-wifi_app_user}"
PGPASS="${LOCAL_DB_PASS:-local_dev_password_2026}"
PGDB="${LOCAL_DB_NAME:-wifi_app_dev}"

PG_BIN_DIR="$(dirname "$(ls /usr/lib/postgresql/*/bin/pg_ctl | sort -V | tail -n 1)")"
export PATH="$PG_BIN_DIR:$PATH"

if [ ! -d "$PGDATA" ]; then
    echo "Initializing isolated local PostgreSQL cluster in $PGDATA (port $PGPORT)..."
    initdb -D "$PGDATA" -U postgres -A trust --encoding=UTF8 --locale=C >/dev/null
    cat >> "$PGDATA/postgresql.conf" <<EOF
listen_addresses = '127.0.0.1'
port = $PGPORT
unix_socket_directories = '/tmp'
EOF
fi

if ! pg_ctl -D "$PGDATA" status >/dev/null 2>&1; then
    echo "Starting local PostgreSQL cluster on 127.0.0.1:$PGPORT..."
    pg_ctl -D "$PGDATA" -l "$PGDATA/postgres.log" start >/dev/null
fi

# Ensure role and database exist
psql -h 127.0.0.1 -p "$PGPORT" -U postgres -d postgres -v ON_ERROR_STOP=1 <<EOSQL >/dev/null
DO \$\$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '$PGUSER') THEN
        CREATE ROLE $PGUSER WITH LOGIN PASSWORD '$PGPASS' SUPERUSER;
    END IF;
END
\$\$;
EOSQL

if ! psql -h 127.0.0.1 -p "$PGPORT" -U postgres -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname = '$PGDB'" | grep -q 1; then
    createdb -h 127.0.0.1 -p "$PGPORT" -U postgres -O "$PGUSER" "$PGDB"
fi

echo "Local development PostgreSQL is ready: jdbc:postgresql://127.0.0.1:${PGPORT}/${PGDB} (user: ${PGUSER})"
