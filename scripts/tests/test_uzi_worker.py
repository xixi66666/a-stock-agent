import json
import os
import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[2]
WORKER = PROJECT_ROOT / "scripts" / "uzi-worker.py"


class UziWorkerTest(unittest.TestCase):
    def test_wraps_official_run_and_emits_structured_bundle(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "UZI-Skill"
            output = Path(directory) / "report"
            root.mkdir()
            (root / "run.py").write_text(textwrap.dedent("""
                import argparse, json
                from pathlib import Path
                parser = argparse.ArgumentParser()
                parser.add_argument("ticker")
                parser.add_argument("--depth", required=True)
                parser.add_argument("--school")
                parser.add_argument("--no-browser", action="store_true")
                parser.add_argument("--output-dir", required=True)
                args = parser.parse_args()
                out = Path(args.output_dir)
                out.mkdir(parents=True, exist_ok=True)
                (out / "raw_data.json").write_text(json.dumps({"sources": [{"provider": "CNINFO", "url": "https://example.test"}]}))
                (out / "dimensions.json").write_text(json.dumps({
                    "companyProfile": {"industry": "食品饮料"},
                    "competitors": {"items": ["同行A"]},
                    "earningsForecast": {"eps": 2.1},
                    "structuredValuation": {"pe": 25}
                }))
                (out / "panel.json").write_text(json.dumps({"rating": "关注"}))
                (out / "synthesis.json").write_text(json.dumps({"summary": "fixture"}))
                (out / "report.meta.json").write_text(json.dumps({"generated_at": "2026-09-13T00:00:00Z"}))
                (out / "index.html").write_text("<html></html>")
            """), encoding="utf-8")

            completed = subprocess.run(
                [sys.executable, str(WORKER), "--uzi-root", str(root), "--code", "600519",
                 "--depth", "deep", "--school", "F", "--output-dir", str(output)],
                check=False, capture_output=True, text=True,
                env={**os.environ, "UZI_TEST_MODE": "1"},
            )

            self.assertEqual(completed.returncode, 0, completed.stderr)
            bundle = json.loads((output / "bundle.json").read_text(encoding="utf-8"))
            self.assertEqual(bundle["schema"], "uzi-bundle-v1")
            self.assertEqual(bundle["ticker"], "600519")
            self.assertEqual(bundle["structured"]["companyProfile"]["industry"], "食品饮料")
            self.assertEqual(bundle["structured"]["competitors"]["items"], ["同行A"])
            self.assertEqual(bundle["structured"]["earningsForecast"]["eps"], 2.1)
            self.assertEqual(bundle["structured"]["structuredValuation"]["pe"], 25)
            self.assertEqual(bundle["sources"][0]["provider"], "CNINFO")


if __name__ == "__main__":
    unittest.main()
