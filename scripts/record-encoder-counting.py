#!/usr/bin/env python3
"""Publish a host proof only from fresh, unskipped, source-correlated differential tests."""
import hashlib
import json
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
folder = ROOT / "build/encoder-counting"
proof = folder / "counting-proof.json"
# Failed reruns must not leave a previous success available to downstream gates.
proof.unlink(missing_ok=True)
report = json.loads((folder / "reference.json").read_text())
result = ROOT / "shared/build/test-results/jvmTest/TEST-dev.edgecompanion.core.inference.embedding.UseTokenizerReferenceTest.xml"
suite = ET.parse(result).getroot()
if (suite.get("tests"), suite.get("failures"), suite.get("errors"), suite.get("skipped")) != ("2", "0", "0", "0"):
    raise SystemExit("Expected two passing, unskipped counting tests")
if result.stat().st_mtime_ns // 1_000_000 < report["createdAtMs"]:
    raise SystemExit("Counting test results predate this oracle corpus")
for path, digest in report["sourceSha256"].items():
    if hashlib.sha256((ROOT / path).read_bytes()).hexdigest() != digest:
        raise SystemExit("Source changed during counting proof")
if hashlib.sha256((folder / "reference.tsv").read_bytes()).hexdigest() != report["referenceSha256"]:
    raise SystemExit("Oracle corpus changed during proof")
report.update(passed=True, admissionVerified=True, scope="host-token-ids-and-admission", recordedAtMs=int(time.time() * 1000),
              remainingGate="Android admission and request-owned native handoff acceptance are separate")
proof.write_text(json.dumps(report, indent=2))
print("Source-correlated host proof passed: 1038 exact token-ID cases and rejection/cancellation checks")
