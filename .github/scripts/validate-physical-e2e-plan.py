#!/usr/bin/env python3

import csv
import sys
from collections import Counter
from pathlib import Path

PLAN = Path("evidence/physical-e2e/run-plan-v1.csv")
EXPECTED_COLUMNS = [
    "planId",
    "objectSet",
    "surface",
    "testCaseId",
    "category",
    "deviceProfile",
    "expectedBehavior",
]
ALLOWED_SETS = {"A", "B", "C", "D"}
ALLOWED_SURFACES = {"wood", "white", "black", "patterned", "venue"}
ALLOWED_CATEGORIES = {"standard", "boundary", "error"}
ALLOWED_DEVICES = {"pixel8", "midrange_arcore", "depth_unsupported"}
ALLOWED_BEHAVIORS = {
    "restoration_evaluated",
    "success_withheld",
    "typed_rejection",
}
REQUIRED_GROUPS = {
    ("A", "wood", "standard"): {f"T{i:02d}" for i in range(1, 13)},
    ("B", "white", "standard"): {f"T{i:02d}" for i in range(1, 13)},
    ("A", "black", "standard"): {f"T{i:02d}" for i in range(1, 9)},
    ("B", "patterned", "standard"): {f"T{i:02d}" for i in range(1, 9)},
    ("C", "venue", "standard"): {f"T{i:02d}" for i in range(1, 9)},
}
REQUIRED_BOUNDARY = {
    ("D", "wood", "T01"),
    ("D", "black", "T03"),
    ("D", "patterned", "T07"),
    ("D", "venue", "T12"),
}


def fail(message: str) -> None:
    raise SystemExit(message)


with PLAN.open(newline="", encoding="utf-8") as source:
    reader = csv.DictReader(source)
    if reader.fieldnames != EXPECTED_COLUMNS:
        fail(f"unexpected columns: {reader.fieldnames}")
    rows = list(reader)

if len(rows) != 60:
    fail(f"expected 60 planned runs, found {len(rows)}")

expected_ids = [f"P{i:03d}" for i in range(1, 61)]
actual_ids = [row["planId"] for row in rows]
if actual_ids != expected_ids:
    fail("plan IDs must be exactly P001 through P060 in order")

for row in rows:
    if row["objectSet"] not in ALLOWED_SETS:
        fail(f"invalid object set: {row}")
    if row["surface"] not in ALLOWED_SURFACES:
        fail(f"invalid surface: {row}")
    if row["category"] not in ALLOWED_CATEGORIES:
        fail(f"invalid category: {row}")
    if row["deviceProfile"] not in ALLOWED_DEVICES:
        fail(f"invalid device profile: {row}")
    if row["expectedBehavior"] not in ALLOWED_BEHAVIORS:
        fail(f"invalid expected behavior: {row}")
    if not (
        len(row["testCaseId"]) == 3
        and row["testCaseId"][0] == "T"
        and row["testCaseId"][1:].isdigit()
        and 1 <= int(row["testCaseId"][1:]) <= 20
    ):
        fail(f"invalid test case: {row}")

for group, expected_cases in REQUIRED_GROUPS.items():
    actual_cases = {
        row["testCaseId"]
        for row in rows
        if (
            row["objectSet"],
            row["surface"],
            row["category"],
        ) == group
    }
    if actual_cases != expected_cases:
        fail(f"incomplete required group {group}: {sorted(actual_cases)}")

error_counts = Counter(
    row["testCaseId"] for row in rows if row["category"] == "error"
)
if error_counts != Counter({f"T{i}": 1 for i in range(13, 21)}):
    fail(f"T13-T20 must appear exactly once as error runs: {error_counts}")

boundary = {
    (row["objectSet"], row["surface"], row["testCaseId"])
    for row in rows
    if row["category"] == "boundary"
}
if boundary != REQUIRED_BOUNDARY:
    fail(f"boundary supplement mismatch: {sorted(boundary)}")

devices = {row["deviceProfile"] for row in rows}
if devices != ALLOWED_DEVICES:
    fail(f"all reference device profiles are required: {sorted(devices)}")

for row in rows:
    case_number = int(row["testCaseId"][1:])
    expected = (
        "success_withheld"
        if 13 <= case_number <= 16
        else "typed_rejection"
        if 17 <= case_number <= 20
        else "restoration_evaluated"
    )
    if row["expectedBehavior"] != expected:
        fail(f"incorrect expected behavior: {row}")

print("Validated 60 physical E2E planned runs")
