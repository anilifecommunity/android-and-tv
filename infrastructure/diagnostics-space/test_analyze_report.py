import json
import tempfile
import unittest
import zipfile
from pathlib import Path

from analyze_report import analyze


class AnalyzeReportTest(unittest.TestCase):
    def test_unicode_next_line_inside_json_value_is_not_treated_as_record_separator(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "report.zip"
            event = {
                "category": "process",
                "name": "process.sample",
                "attributes": {"state": "foreground\u0085service", "pssKb": 10},
            }
            ui_jank = {
                "category": "render",
                "name": "jank.sample_completed",
                "attributes": {"playbackActive": False, "jankFrames": 2},
            }
            playback_cadence = {
                "category": "render",
                "name": "jank.sample_completed",
                "attributes": {"playbackActive": True, "jankFrames": 200},
            }
            with zipfile.ZipFile(report, "w") as archive:
                archive.writestr("manifest.json", json.dumps({"appVersion": "test"}))
                archive.writestr(
                    "events.jsonl",
                    b"\n".join(
                        json.dumps(row, ensure_ascii=False).encode("utf-8")
                        for row in (event, ui_jank, playback_cadence)
                    ) + b"\n",
                )

            result = analyze(report)

            self.assertEqual(result["eventCount"], 3)
            self.assertEqual(result["performance"]["samples"], 1)
            self.assertEqual(result["performance"]["jankWindows"], 1)
            self.assertEqual(result["performance"]["playbackCadenceWindows"], 1)


if __name__ == "__main__":
    unittest.main()
