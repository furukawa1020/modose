#!/usr/bin/env python3

import argparse
import csv
import json
import math
import statistics
from pathlib import Path


def percentile_nearest_rank(values: list[float], percentile: float) -> float:
    ordered = sorted(values)
    rank = max(1, math.ceil(percentile * len(ordered)))
    return ordered[rank - 1]


def evaluate(
    plan_path: Path,
    records_directory: Path,
) -> tuple[dict[str, object], list[str]]:
    with plan_path.open(newline="", encoding="utf-8") as source:
        plan_rows = list(csv.DictReader(source))
    plan = {row["planId"]: row for row in plan_rows}
    failures: list[str] = []
    if len(plan_rows) != 60 or len(plan) != 60:
        failures.append("plan must contain 60 unique runs")

    records: dict[str, dict[str, object]] = {}
    if not records_directory.is_dir():
        failures.append("records directory does not exist")
    else:
        for path in sorted(records_directory.glob("*.json")):
            try:
                record = json.loads(path.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError):
                failures.append(f"{path.name}: unreadable JSON")
                continue
            run_id = record.get("runId")
            if run_id != path.stem:
                failures.append(f"{path.name}: runId must equal file stem")
                continue
            if run_id not in plan:
                failures.append(f"{path.name}: unplanned run")
                continue
            if run_id in records:
                failures.append(f"{path.name}: duplicate run")
                continue
            row = plan[run_id]
            for key in ("objectSet", "surface", "testCaseId"):
                if record.get(key) != row[key]:
                    failures.append(f"{path.name}: {key} differs from plan")
            if record.get("videoFileName") != f"{run_id}.mp4":
                failures.append(f"{path.name}: video does not match runId")
            if not isinstance(record.get("falseSuccess"), bool):
                failures.append(f"{path.name}: falseSuccess must be boolean")
            errors = record.get("positionErrorsCm")
            if not isinstance(errors, list):
                failures.append(f"{path.name}: positionErrorsCm must be an array")
            elif any(
                not isinstance(value, (int, float))
                or isinstance(value, bool)
                or not math.isfinite(value)
                or value < 0
                or value > 200
                for value in errors
            ):
                failures.append(f"{path.name}: invalid position error")
            records[run_id] = record

    missing = set(plan) - set(records)
    if missing:
        failures.append(f"{len(missing)} planned records are missing")
    if len(records) != 60:
        failures.append(f"expected 60 records, found {len(records)}")

    restoration_ids = {
        run_id
        for run_id, row in plan.items()
        if row["expectedBehavior"] == "restoration_evaluated"
    }
    restoration_records = [
        records[run_id] for run_id in restoration_ids if run_id in records
    ]
    verified = sum(
        record.get("finalResult") == "verified"
        for record in restoration_records
    )
    success_rate = (
        verified / len(restoration_ids) * 100.0
        if restoration_ids
        else 0.0
    )

    false_successes = sum(
        record.get("falseSuccess") is True for record in records.values()
    )
    false_success_rate = false_successes / 60 * 100.0

    position_errors = [
        float(value)
        for run_id in restoration_ids
        if run_id in records
        for value in records[run_id].get("positionErrorsCm", [])
        if isinstance(value, (int, float)) and not isinstance(value, bool)
    ]
    median_error = (
        statistics.median(position_errors) if position_errors else None
    )
    p90_error = (
        percentile_nearest_rank(position_errors, 0.90)
        if position_errors
        else None
    )

    rejection_ids = {
        run_id
        for run_id, row in plan.items()
        if row["expectedBehavior"] == "typed_rejection"
    }
    rejected_boundaries = sum(
        records.get(run_id, {}).get("finalResult") == "rejected"
        for run_id in rejection_ids
    )

    if success_rate < 85.0:
        failures.append(f"final success rate below 85%: {success_rate:.2f}%")
    if false_success_rate > 2.0:
        failures.append(
            f"false success rate above 2%: {false_success_rate:.2f}%"
        )
    if median_error is None or median_error > 3.0:
        failures.append("median position error missing or above 3cm")
    if p90_error is None or p90_error > 5.0:
        failures.append("p90 position error missing or above 5cm")
    if rejected_boundaries != len(rejection_ids):
        failures.append("not all typed-rejection runs were rejected")

    summary: dict[str, object] = {
        "plannedRuns": len(plan),
        "recordedRuns": len(records),
        "restorationRuns": len(restoration_ids),
        "verifiedRestorationRuns": verified,
        "finalSuccessRatePercent": round(success_rate, 4),
        "falseSuccessCount": false_successes,
        "falseSuccessRatePercent": round(false_success_rate, 4),
        "positionErrorSampleCount": len(position_errors),
        "medianPositionErrorCm": median_error,
        "p90PositionErrorCm": p90_error,
        "typedRejectionRuns": len(rejection_ids),
        "acceptedTypedRejections": rejected_boundaries,
        "passed": not failures,
    }
    return summary, failures


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--plan",
        type=Path,
        default=Path("evidence/physical-e2e/run-plan-v1.csv"),
    )
    parser.add_argument("--records-dir", type=Path, required=True)
    args = parser.parse_args()
    summary, failures = evaluate(args.plan, args.records_dir)
    print(json.dumps(summary, indent=2, sort_keys=True))
    if failures:
        raise SystemExit("\n".join(failures))


if __name__ == "__main__":
    main()
