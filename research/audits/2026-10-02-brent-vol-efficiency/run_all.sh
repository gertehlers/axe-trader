#!/usr/bin/env bash
# Reproduce the whole audit package. Deterministic (fixed seeds); ~4 minutes on an M-series Mac.
# Usage, from anywhere inside the repository:  bash research/audits/2026-10-02-brent-vol-efficiency/run_all.sh
set -euo pipefail
ROOT="$(git rev-parse --show-toplevel)"
PKG="$ROOT/research/audits/2026-10-02-brent-vol-efficiency"
cd "$ROOT/research/engine"
PY="${PY:-.venv/bin/python}"
if [ ! -f "$ROOT/data/axe-trader.sqlite" ]; then
  echo "missing $ROOT/data/axe-trader.sqlite (gitignored; see README.md 'Data')" >&2; exit 2
fi

echo "== 1/3 audit_checks.py (re-runs the original experiment unchanged, then audits it)"
PYTHONPATH=. "$PY" "$PKG/audit_checks.py" > "$PKG/outputs/audit_checks_stdout.txt"
grep -q "identical to committed 2026-10-02-brent-vol-efficiency-swings.txt: True" "$PKG/outputs/audit_checks_output.txt" \
  && echo "   original output reproduced byte-for-byte" || { echo "   ORIGINAL OUTPUT DIFFERS from the committed .txt"; exit 1; }

echo "== 2/3 rerun_original_with_corrected_zigzag.py (original report, zigzag swapped in memory)"
PYTHONPATH=. "$PY" "$PKG/rerun_original_with_corrected_zigzag.py" > /dev/null

echo "== 3/3 verify_independently.py (separate implementation from raw SQLite; exits 1 on disagreement)"
"$PY" "$PKG/verify_independently.py" | tee "$PKG/outputs/verify_independently_output.txt" | tail -3
echo "done; outputs in $PKG/outputs and the package root"
