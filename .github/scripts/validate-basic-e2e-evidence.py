#!/usr/bin/env python3

import argparse
import csv
import json
from pathlib import Path


class EvidenceValidationError(ValueError):
    pass


def load_plan(path: Path) -> dict[str, dict[str, str]]:
    with path.open(newline="", encoding="utf-8") as source:
        rows = list(csv.DictReader(source))
    plan = {row["planId"]: row for row in rows}
    if len(plan) != len(rows):
        raise EvidenceValidationError("plan contains duplicate planId")
    return plan


def basic_plan_ids(plan: dict[str, dict[str, str]]) -> set[str]:
    return {
        plan_id
        for plan_id, row in plan.items()
        if row["category"] == "standard" and row["objectSet"] in {"A", "B"}
    }


def validate_basic_evidence(
    plan_path: Path,
    records_directory: Path,
    videos_directory: Path,
    require_complete: bool,
) -> int:
    plan = load_plan(plan_path)
    expected_ids = basic_plan_ids(plan)
    if len(expected_ids) != 40:
        raise EvidenceValidationError(
            f"basic scope must contain 40 planned runs, found {len(expected_ids)}"
        )
    if not records_directory.is_dir():
        raise EvidenceValidationError("records directory does not exist")
    if not videos_directory.is_dir():
        raise EvidenceValidationError("videos directory does not exist")

    observed_ids: set[str] = set()
    for record_path in sorted(records_directory.glob("*.json")):
        try:
            record = json.loads(record_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            raise EvidenceValidationError(
                f"cannot read record {record_path.name}: {error}"
            ) from error

        run_id = record.get("runId")
        if not isinstance(run_id, str) or run_id != record_path.stem:
            raise EvidenceValidationError(
                f"{record_path.name}: runId must equal the file stem"
            )
        if run_id not in plan:
            raise EvidenceValidationError(
                f"{record_path.name}: runId is not present in the plan"
            )
        if run_id not in expected_ids:
            continue
        if run_id in observed_ids:
            raise EvidenceValidationError(f"duplicate basic runId: {run_id}")

        planned = plan[run_id]
        comparisons = {
            "objectSet": planned["objectSet"],
            "surface": planned["surface"],
            "testCaseId": planned["testCaseId"],
        }
        for key, expected in comparisons.items():
            if record.get(key) != expected:
                raise EvidenceValidationError(
                    f"{record_path.name}: {key} must be {expected}"
                )

        expected_video = f"{run_id}.mp4"
        if record.get("videoFileName") != expected_video:
            raise EvidenceValidationError(
                f"{record_path.name}: videoFileName must be {expected_video}"
            )
        video_path = videos_directory / expected_video
        if (
            not video_path.is_file()
            or video_path.is_symlink()
            or video_path.stat().st_size <= 0
        ):
            raise EvidenceValidationError(
                f"{record_path.name}: non-empty regular video is required"
            )
        observed_ids.add(run_id)

    missing = expected_ids - observed_ids
    if require_complete and missing:
        preview = ", ".join(sorted(missing)[:5])
        raise EvidenceValidationError(
            f"basic evidence is incomplete: {len(missing)} missing ({preview})"
        )
    return len(observed_ids)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--plan",
        type=Path,
        default=Path("evidence/physical-e2e/run-plan-v1.csv"),
    )
    parser.add_argument("--records-dir", type=Path, required=True)
    parser.add_argument("--videos-dir", type=Path, required=True)
    parser.add_argument("--allow-incomplete", action="store_true")
    args = parser.parse_args()

    try:
        count = validate_basic_evidence(
            args.plan,
            args.records_dir,
            args.videos_dir,
            require_complete=not args.allow_incomplete,
        )
    except EvidenceValidationError as error:
        raise SystemExit(str(error)) from error
    print(f"Validated {count} basic physical E2E evidence records")


if __name__ == "__main__":
    main()
