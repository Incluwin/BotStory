import json
import tempfile
import unittest
from pathlib import Path

import compare_policies as cp


class Verdicts(unittest.TestCase):
    live = [100, 101, 99, 100, 102, 98, 100, 100]

    def test_clear_win(self):
        self.assertTrue(cp.judge([x * 1.10 for x in self.live], self.live)["verdict"].startswith("candidate wins by 10"))

    def test_a_real_gain_below_the_margin_is_not_adopted(self):
        self.assertEqual("priors won", cp.judge([x * 1.01 for x in self.live], self.live)["verdict"])

    def test_equal_or_worse(self):
        self.assertEqual("priors won", cp.judge(self.live, self.live)["verdict"])
        self.assertEqual("priors won", cp.judge([x * 0.8 for x in self.live], self.live)["verdict"])

    def test_noise_is_not_enough_data(self):
        noisy = [60, 150, 90, 140, 80, 130, 100, 120]
        self.assertEqual("not enough data", cp.judge(noisy, self.live)["verdict"])

    def test_few_pairs_or_a_capped_reading_never_win(self):
        self.assertEqual("not enough data", cp.judge([200] * 5, [100] * 5)["verdict"])
        self.assertEqual("not enough data", cp.judge([x * 2 for x in self.live], self.live, True)["verdict"])

    def test_t_quantile(self):
        self.assertAlmostEqual(3.499, cp.t_quantile(0.995, 7), delta=0.06)
        self.assertAlmostEqual(2.807, cp.t_quantile(0.995, 23), delta=0.01)


class Files(unittest.TestCase):
    def test_runs_pool_and_staging_keeps_what_is_adopted(self):
        with tempfile.TemporaryDirectory() as d:
            tree = Path(d)
            policy = tree / "scripts/config/agents/combat-policy"
            (policy / "candidates").mkdir(parents=True)
            (policy / "candidates/232.json").write_text(json.dumps(
                {"jobId": 232, "scenarios": {"multi": {"adopted": False, "decisions": 500, "table": {"2-3 targets": {}}}}}))
            (policy / "232.json").write_text(json.dumps({"jobId": 232, "scenarios": {"single": {"adopted": True}}}))
            (tree / "logs").mkdir()
            line = {"jobId": 232, "job": "BISHOP", "scenario": "multi", "level": 140, "decisions": 500, "seconds": 20,
                    "deaths": 0, "capped": False, "candidateDps": [1.0] * 8, "liveDps": [0.5] * 8}
            (tree / "logs/arena-comparisons.jsonl").write_text((json.dumps(line) + "\n") * 2)

            got = cp.readings(tree)[(232, "multi")]
            self.assertEqual((2, 16), (got["looks"], len(got["candidate"])))
            staged = json.loads(cp.stage(tree, 232, [{"scenario": "multi", "candidate": 1, "live": 0.5}],
                                         tree / "staged").read_text())
            self.assertTrue(staged["scenarios"]["single"]["adopted"] and staged["scenarios"]["multi"]["adopted"])
            self.assertFalse(json.loads((policy / "candidates/232.json").read_text())["scenarios"]["multi"]["adopted"])


if __name__ == "__main__":
    unittest.main()
