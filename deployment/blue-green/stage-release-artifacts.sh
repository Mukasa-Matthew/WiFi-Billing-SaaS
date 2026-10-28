#!/usr/bin/env bash
# ==============================================================================
# WiFi-Billing SaaS — Secure Release Artifact Staging & Verification Helper
# Usage:
#   ./stage-release-artifacts.sh <COMMIT_SHA> <STAGING_SOURCE_DIR>
# ==============================================================================
set -euo pipefail

COMMIT_SHA="${1:-}"
SOURCE_DIR="${2:-}"

if [ -z "${COMMIT_SHA}" ] || [ -z "${SOURCE_DIR}" ]; then
    echo "ERROR: Target commit SHA and staging source directory are required." >&2
    echo "Usage: $0 <COMMIT_SHA> <STAGING_SOURCE_DIR>" >&2
    exit 1
fi

APP_DIR="${APP_DIR:-/var/www/wifi-billing}"
RELEASES_DIR="${APP_DIR}/releases"
TARGET_RELEASE_DIR="${RELEASES_DIR}/release-${COMMIT_SHA}"
TEMP_STAGE_DIR="${RELEASES_DIR}/.stage-${COMMIT_SHA}-$$"

echo "=== Staging Release Artifacts for Commit ${COMMIT_SHA} ==="

# 1. Validate required source artifacts
JAR_FILE="${SOURCE_DIR}/api-0.0.1-SNAPSHOT.jar"
TAR_FILE="${SOURCE_DIR}/frontend-standalone.tar.gz"
BACKEND_SHA="${SOURCE_DIR}/backend.sha256"
FRONTEND_SHA="${SOURCE_DIR}/frontend.sha256"

for f in "${JAR_FILE}" "${TAR_FILE}" "${BACKEND_SHA}" "${FRONTEND_SHA}"; do
    if [ ! -f "${f}" ]; then
        echo "SECURITY ERROR: Missing required artifact or manifest: ${f}" >&2
        exit 1
    fi
done

# 2. Verify Checksums in staging source
echo "Verifying backend JAR checksum..."
( cd "${SOURCE_DIR}" && sha256sum -c backend.sha256 ) || {
    echo "SECURITY ERROR: Backend JAR checksum verification failed!" >&2
    exit 1
}

echo "Verifying frontend standalone bundle checksum..."
( cd "${SOURCE_DIR}" && sha256sum -c frontend.sha256 ) || {
    echo "SECURITY ERROR: Frontend standalone checksum verification failed!" >&2
    exit 1
}

# 3. Create isolated temporary staging directory
mkdir -p "${TEMP_STAGE_DIR}/Backend/target" "${TEMP_STAGE_DIR}/Frontend"
cp "${JAR_FILE}" "${TEMP_STAGE_DIR}/Backend/target/api-0.0.1-SNAPSHOT.jar"
cp "${TAR_FILE}" "${TEMP_STAGE_DIR}/frontend-standalone.tar.gz"

# Extract Next.js standalone bundle in temp staging
echo "Extracting standalone frontend bundle..."
tar -xzf "${TEMP_STAGE_DIR}/frontend-standalone.tar.gz" -C "${TEMP_STAGE_DIR}/Frontend"

if [ ! -f "${TEMP_STAGE_DIR}/Frontend/.next/standalone/server.js" ]; then
    echo "ERROR: Extracted bundle does not contain .next/standalone/server.js!" >&2
    rm -rf "${TEMP_STAGE_DIR}"
    exit 1
fi

# Write combined release manifest
cat << EOF_MANIFEST > "${TEMP_STAGE_DIR}/release.sha256"
$(cat "${BACKEND_SHA}")
$(cat "${FRONTEND_SHA}")
EOF_MANIFEST
echo "${COMMIT_SHA}" > "${TEMP_STAGE_DIR}/commit.txt"

# 4. Atomic promotion to release directory
if [ -d "${TARGET_RELEASE_DIR}" ]; then
    echo "Release directory ${TARGET_RELEASE_DIR} already exists. Verifying integrity..."
    ( cd "${TARGET_RELEASE_DIR}" && sha256sum -c release.sha256 ) || {
        echo "ERROR: Existing release directory checksum failed!" >&2
        rm -rf "${TEMP_STAGE_DIR}"
        exit 1
    }
    rm -rf "${TEMP_STAGE_DIR}"
else
    mv "${TEMP_STAGE_DIR}" "${TARGET_RELEASE_DIR}"
    echo "Release ${COMMIT_SHA} staged atomically into ${TARGET_RELEASE_DIR}."
fi

echo "Artifact staging and verification completed successfully."
