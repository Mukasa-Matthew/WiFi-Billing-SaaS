#!/usr/bin/env bash
# ==============================================================================
# AirPairy — Approval A Installer Alias
# Delegates directly to execute-approval-a.sh to maintain a single authoritative
# installation script and eliminate conflicting instructions.
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "${SCRIPT_DIR}/execute-approval-a.sh" "$@"
