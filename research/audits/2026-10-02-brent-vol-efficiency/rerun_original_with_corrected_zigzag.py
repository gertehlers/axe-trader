"""Re-run the ORIGINAL experiment's full report with only its zigzag swapped for the audit's corrected one.

The original file is not modified: its source is read, the `zigzag` function is replaced in memory with
`zigzag_corrected` (copied verbatim from audit_checks.py), and the result is executed. Output goes to
outputs/original_with_corrected_zigzag.txt so it can be diffed against the committed original output.

    cd research/engine && PYTHONPATH=. .venv/bin/python ../audits/2026-10-02-brent-vol-efficiency/rerun_original_with_corrected_zigzag.py
"""
import contextlib
import io
from pathlib import Path

HERE = Path(__file__).resolve().parent
ORIG = HERE.parents[1] / "experiments/2026-10-02-brent-vol-efficiency-swings.py"
src = ORIG.read_text()
audit = (HERE / "audit_checks.py").read_text()
start, end = src.index("def zigzag(threshold):"), src.index("big = zigzag(")
fixed = audit[audit.index("def zigzag_corrected(threshold):"):audit.index("ZIGZAGS = {")]
patched = src[:start] + fixed.replace("def zigzag_corrected(", "def zigzag(") + "\n" + src[end:]
assert patched.count("def zigzag(") == 1
buf = io.StringIO()
with contextlib.redirect_stdout(buf):
    exec(compile(patched, str(ORIG) + " [corrected zigzag]", "exec"), {"__file__": str(ORIG), "__name__": "__main__"})
(HERE / "outputs/original_with_corrected_zigzag.txt").write_text(buf.getvalue())
print(buf.getvalue())
