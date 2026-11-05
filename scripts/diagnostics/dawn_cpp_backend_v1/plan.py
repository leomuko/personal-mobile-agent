"""31 exact queries; driver flags and original 72-case C++ matrix are unchanged."""
import importlib.util
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3]
s=importlib.util.spec_from_file_location('cpp_backend_base_plan',ROOT/'scripts/diagnostics/dawn_cpp_toolchain_v1/plan.py');old=importlib.util.module_from_spec(s);s.loader.exec_module(old)
CLANG=old.CLANG;LD=old.LD;OTOOL=old.OTOOL;SDK=old.SDK;RESOURCE=old.RESOURCE;BIN=old.BIN;BACKEND=BIN/'otool-classic'


def commands(attempt,native_files):
    if len(native_files)!=8 or str(BACKEND) not in native_files:raise ValueError('backend scope requires exactly eight native payloads')
    result=old.commands(attempt,native_files)
    if len(result)!=31:raise ValueError('backend scope requires exactly31 queries')
    return result
