#!/usr/bin/env python3
import argparse
from pathlib import Path
from encoder_counting import create_vectors, corpus

parser = argparse.ArgumentParser(description="Prepare a synthetic token-ID oracle corpus for the pinned USE graph")
parser.add_argument("--model", type=Path)
parser.add_argument("--reference", type=Path)
parser.add_argument("--transport-snapshot", type=Path)
parser.add_argument("--seed-only", action="store_true")
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
if args.seed_only:
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "transport-inputs.txt").write_text("".join(text.encode("utf-8").hex() + "\n" for text in corpus()))
else:
    if not all((args.model, args.reference, args.transport_snapshot)):
        parser.error("--model, --reference and --transport-snapshot are required for the oracle")
    print(f"Prepared {create_vectors(args.model, args.reference, args.output, args.transport_snapshot)} synthetic cases; admission proof still pending")
