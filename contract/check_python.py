"""Run the shared contract against the Python service to prove both versions agree.

Usage (from the root of the Python repo transfer-ledger-service):
    python path/to/contract/check_python.py path/to/contract/validation_cases.json
"""
import json
import sys

from ledger.domain import ValidationError, parse_amount

cases = json.load(open(sys.argv[1], encoding="utf-8"))["amounts"]
mismatches = 0
for case in cases:
    try:
        ok = parse_amount(case["raw"]) == case.get("cents")
    except ValidationError as exc:
        ok = exc.problems == [case.get("error")]
    if not ok:
        mismatches += 1
        print("MISMATCH", case)
print(f"{len(cases) - mismatches}/{len(cases)} cases agree")
sys.exit(1 if mismatches else 0)
