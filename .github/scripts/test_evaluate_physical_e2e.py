#!/usr/bin/env python3

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("evaluate-physical-e2e.py")
SPEC = importlib.util.spec_from_file_location("e2e_evaluator", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class PhysicalE2eEvaluatorTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.plan = self.root / "plan.csv"
        self.records = self.root / "records"
        self.records.mkdir()
        lines = [
            "planId,objectSet,surface,testCaseId,category,"
            "deviceProfile,expectedBehavior"
        ]
        for number in range(1, 61):
            behavior = (
                "restoration_evaluated"
                if number <= 52
                else "success_withheld"
                if number <= 56
                else "typed_rejection"
            )
            case = (
                f"T{((number - 1) % 12) + 1:02d}"
                if number <= 52
                else f"T{number - 40}"
            )
            category = "standard" if number <= 52 else "error"
            lines.append(
                f"P{number:03d},A,wood,{case},{category},"
                f"pixel8,{behavior}"
            )
        self.plan.write_text("\n".join(lines) + "\n", encoding="utf-8")
        for number in range(1, 61):
            final_result = (
                "rejected"
                if number >= 57
                else "uncertain"
                if number >= 53
                else "verified"
            )
            self.write_record(number, finalResult=final_result)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def write_record(self, number: int, **updates: object) -> None:
        run_id = f"P{number:03d}"
        case = (
            f"T{((number - 1) % 12) + 1:02d}"
            if number <= 52
            else f"T{number - 40}"
        )
        record = {
            "runId": run_id,
            "objectSet": "A",
            "surface": "wood",
            "testCaseId": case,
            "videoFileName": f"{run_id}.mp4",
            "falseSuccess": False,
            "positionErrorsCm": [2.0] if number <= 52 else [],
            "finalResult": "verified",
        }
        record.update(updates)
        (self.records / f"{run_id}.json").write_text(
            json.dumps(record),
            encoding="utf-8",
        )

    def evaluate(self) -> tuple[dict[str, object], list[str]]:
        return MODULE.evaluate(self.plan, self.records)

    def test_passes_all_product_thresholds(self) -> None:
        summary, failures = self.evaluate()
        self.assertEqual([], failures)
        self.assertTrue(summary["passed"])
        self.assertEqual(100.0, summary["finalSuccessRatePercent"])
        self.assertEqual(0.0, summary["falseSuccessRatePercent"])
        self.assertEqual(2.0, summary["medianPositionErrorCm"])
        self.assertEqual(2.0, summary["p90PositionErrorCm"])
        self.assertEqual(4, summary["acceptedTypedRejections"])

    def test_fails_when_final_success_rate_is_below_85_percent(self) -> None:
        for number in range(1, 9):
            self.write_record(number, finalResult="needs_correction")
        summary, failures = self.evaluate()
        self.assertFalse(summary["passed"])
        self.assertTrue(any("below 85%" in item for item in failures))

    def test_fails_when_two_false_successes_exceed_two_percent(self) -> None:
        self.write_record(1, falseSuccess=True)
        self.write_record(2, falseSuccess=True)
        summary, failures = self.evaluate()
        self.assertEqual(3.3333, summary["falseSuccessRatePercent"])
        self.assertTrue(any("above 2%" in item for item in failures))

    def test_uses_nearest_rank_for_p90_position_error(self) -> None:
        for number in range(1, 7):
            self.write_record(number, positionErrorsCm=[5.1])
        summary, failures = self.evaluate()
        self.assertEqual(5.1, summary["p90PositionErrorCm"])
        self.assertTrue(any("p90 position error" in item for item in failures))

    def test_fails_missing_record_and_typed_rejection(self) -> None:
        (self.records / "P001.json").unlink()
        self.write_record(57, finalResult="verified")
        summary, failures = self.evaluate()
        self.assertEqual(59, summary["recordedRuns"])
        self.assertTrue(any("planned records are missing" in item for item in failures))
        self.assertTrue(any("typed-rejection" in item for item in failures))


if __name__ == "__main__":
    unittest.main()
