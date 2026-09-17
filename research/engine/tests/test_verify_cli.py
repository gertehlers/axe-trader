import json

from engine.instruments import write_instruments
from engine.verify import main

HOURS = {day: [[600, 720]] for day in ["mon", "tue", "wed", "thu", "fri"]} | {"sat": [], "sun": []}


def test_writes_a_report_for_each_requested_epic(db_factory, tmp_path, capsys):
    rows = [("TEST", f"2024-01-01T11:{m:02d}:00Z", 100.0, 100.2) for m in range(60)]
    db = db_factory(rows)
    instruments = tmp_path / "instruments.yaml"
    write_instruments(instruments, {
        "TEST": {"epic": "TEST", "opening_hours": HOURS},
        "EMPTY": {"epic": "EMPTY", "opening_hours": HOURS},
    }, fetched_on="2026-09-17")
    out = tmp_path / "report.json"

    main(["--db", str(db), "--instruments", str(instruments), "--out", str(out)])

    report = json.loads(out.read_text())
    assert report["instruments"]["TEST"]["passed"] is True
    assert report["instruments"]["EMPTY"]["failures"] == ["no_data"]
    assert report["thresholds"]["hole_minutes"] == 30
    printed = capsys.readouterr().out
    assert "TEST" in printed and "PASS" in printed and "EMPTY" in printed and "FAIL" in printed
