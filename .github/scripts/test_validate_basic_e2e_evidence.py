#!/usr/bin/env python3

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("validate-basic-e2e-evidence.py")
SPEC = importlib.util.spec_from_file_location("basic_evidence", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class BasicEvidenceValidatorTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.plan = self.root / "plan.csv"
        self.records = self.root / "records"
        self.videos = self.root / "videos"
        self.records.mkdir()
        self.videos.mkdir()
        lines = [
            "planId,objectSet,surface,testCaseId,category,"
            "deviceProfile,expectedBehavior"
        ]
        for number in range(1, 41):
            object_set = "A" if number <= 20 else "B"
            surface = "wood" if object_set == "A" else "white"
            test_case = f"T{((number - 1) % 12) + 1:02d}"
            lines.append(
                f"P{number:03d},{object_set},{surface},{test_case},"
                "standard,pixel8,restoration_evaluated"
            )
        self.plan.write_text("\n".join(lines) + "\n", encoding="utf-8")

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def write_evidence(self, plan_id: str, **overrides: object) -> None:
        number = int(plan_id[1:])
        object_set = "A" if number <= 20 else "B"
        record = {
            "runId": plan_id,
            "objectSet": object_set,
            "surface": "wood" if object_set == "A" else "white",
            "testCaseId": f"T{((number - 1) % 12) + 1:02d}",
            "videoFileName": f"{plan_id}.mp4",
        }
        record.update(overrides)
        (self.records / f"{plan_id}.json").write_text(
            json.dumps(record),
            encoding="utf-8",
        )
        (self.videos / f"{plan_id}.mp4").write_bytes(b"video")

    def validate(self, require_complete: bool = False) -> int:
        return MODULE.validate_basic_evidence(
            self.plan,
            self.records,
            self.videos,
            require_complete,
        )

    def test_accepts_matching_partial_evidence(self) -> None:
        self.write_evidence("P001")
        self.assertEqual(1, self.validate())

    def test_accepts_exactly_40_complete_records(self) -> None:
        for number in range(1, 41):
            self.write_evidence(f"P{number:03d}")
        self.assertEqual(40, self.validate(require_complete=True))

    def test_rejects_plan_metadata_mismatch(self) -> None:
        self.write_evidence("P001", surface="black")
        with self.assertRaisesRegex(
            MODULE.EvidenceValidationError,
            "surface must be wood",
        ):
            self.validate()

    def test_rejects_missing_video(self) -> None:
        self.write_evidence("P001")
        (self.videos / "P001.mp4").unlink()
        with self.assertRaisesRegex(
            MODULE.EvidenceValidationError,
            "non-empty regular video is required",
        ):
            self.validate()

    def test_rejects_video_name_not_bound_to_run(self) -> None:
        self.write_evidence("P001", videoFileName="P002.mp4")
        with self.assertRaisesRegex(
            MODULE.EvidenceValidationError,
            "videoFileName must be P001.mp4",
        ):
            self.validate()

    def test_strict_mode_rejects_incomplete_scope(self) -> None:
        self.write_evidence("P001")
        with self.assertRaisesRegex(
            MODULE.EvidenceValidationError,
            "39 missing",
        ):
            self.validate(require_complete=True)

    def test_rejects_unplanned_record(self) -> None:
        record = {
            "runId": "P999",
            "objectSet": "A",
            "surface": "wood",
            "testCaseId": "T01",
            "videoFileName": "P999.mp4",
        }
        (self.records / "P999.json").write_text(
            json.dumps(record),
            encoding="utf-8",
        )
        with self.assertRaisesRegex(
            MODULE.EvidenceValidationError,
            "not present in the plan",
        ):
            self.validate()


if __name__ == "__main__":
    unittest.main()
