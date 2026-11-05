#!/usr/bin/env bash
# ==============================================================================
# [DEPRECATED] AppCDS Generator - Replaced by Project Leyden AOT Cache (JEP 483 / 514)
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "Warning: AppCDS has been replaced by Project Leyden AOT Cache (JEP 483 / 514)."
echo "Forwarding to generate-aot-cache.sh..."
exec "${SCRIPT_DIR}/generate-aot-cache.sh" "$@"
