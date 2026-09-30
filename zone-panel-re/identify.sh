#!/usr/bin/env bash
set -uo pipefail
cd "$(dirname "$0")"

# Read-only: DP/AP/ROM-table discovery is in the verbose connect log; CPUID and
# ROM table PIDRs give the core and the JEP106 designer of the SoC.
.venv/bin/pyocd commander -W -v -O auto_unlock=false \
  -c "read32 0xE000ED00" \
  -c "read32 0xE00FFFD0" \
  -c "read32 0xE00FFFE0 16" \
  2>&1 | tee "dumps/identify-$(date +%Y%m%d-%H%M%S).log"
