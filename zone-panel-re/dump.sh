#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

base=${1:?usage: dump.sh <base> <size>   e.g. dump.sh 0x08000000 0x40000}
size=${2:?usage: dump.sh <base> <size>}
stamp=$(date +%Y%m%d-%H%M%S)

for n in 1 2; do
  .venv/bin/pyocd commander -W -O auto_unlock=false \
    -c "savemem $base $size dumps/flash-$base-$stamp-$n.bin"
done

a=dumps/flash-$base-$stamp-1.bin
b=dumps/flash-$base-$stamp-2.bin
shasum -a 256 "$a" "$b"
if cmp -s "$a" "$b"; then echo "MATCH: both reads identical"; else echo "MISMATCH: re-run at lower frequency (-f 100000)"; fi

.venv/bin/python - "$a" <<'EOF'
import sys
d = open(sys.argv[1], "rb").read()
ff, zz = d.count(0xFF) / len(d), d.count(0x00) / len(d)
print(f"{len(d)} bytes, {ff:.1%} 0xFF, {zz:.1%} 0x00")
if ff > 0.99 or zz > 0.99:
    print("WARNING: nearly uniform - likely read-protected or wrong base address")
EOF
