#!/usr/bin/env python3
"""Build the host oracle from hash-pinned upstream files; no weights or native app code."""
import hashlib
import json
import os
import shutil
import subprocess
import tarfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "build/encoder-counting-toolchain"
LOCK = json.loads((ROOT / "scripts/encoder-reference/upstream.json").read_text())


def fetch(url, target, digest, limit):
    if target.exists():
        data = target.read_bytes()
    else:
        with urllib.request.urlopen(url, timeout=40) as response:
            data = response.read(limit + 1)
    if len(data) > limit or hashlib.sha256(data).hexdigest() != digest:
        raise SystemExit("Upstream digest/size mismatch: " + target.name)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    fb = LOCK["flatbuffers"]
    archive = OUT / "flatbuffers.tar.gz"
    fetch(fb["url"], archive, fb["sha256"], 10 * 1024 * 1024)
    with tarfile.open(archive) as source:
        safe = []
        for member in source.getmembers():
            parts = Path(member.name).parts
            if not parts or parts[0] != fb["directory"] or ".." in parts:
                raise SystemExit("Unsafe upstream archive member")
            # The release contains unrelated Java/TS test symlinks. The C++ toolchain needs none.
            if member.isfile() or member.isdir():
                if not (OUT / member.name).resolve().is_relative_to(OUT.resolve()):
                    raise SystemExit("Unsafe extraction target")
                safe.append(member)
        source.extractall(OUT, members=safe)
    for name, digest in LOCK["files"].items():
        fetch(LOCK["base"] + name, OUT / name, digest, 128 * 1024)
    cmake = os.environ.get("CMAKE") or shutil.which("cmake")
    if not cmake:
        sdk = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Library/Android/sdk")))
        cmake = str(sdk / "cmake/3.22.1/bin/cmake")
    build = OUT / "flatbuffers-build"
    subprocess.run([cmake, "-S", str(OUT / fb["directory"]), "-B", str(build),
                    "-DFLATBUFFERS_BUILD_TESTS=OFF", "-DFLATBUFFERS_BUILD_FLATLIB=OFF", "-DCMAKE_BUILD_TYPE=Release"], check=True)
    subprocess.run([cmake, "--build", str(build), "--target", "flatc", "-j", "4"], check=True)
    prefix = OUT / "mediapipe/tasks/cc/text/custom_ops/sentencepiece"
    subprocess.run([str(build / "flatc"), "--cpp", "-o", str(prefix), str(prefix / "config.fbs"), str(prefix / "encoder_config.fbs")], check=True)
    subprocess.run([os.environ.get("CXX", "clang++"), "-std=c++17", "-O2", "-I" + str(OUT),
                    "-I" + str(OUT / fb["directory"] / "include"), str(ROOT / "scripts/encoder-reference/main.cc"),
                    str(prefix / "optimized_encoder.cc"), "-o", str(OUT / "oracle")], check=True)
    print(OUT / "oracle")


if __name__ == "__main__":
    main()
