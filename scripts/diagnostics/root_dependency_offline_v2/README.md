# Offline retained-source inspection v2

Implements the separately reviewed v2 protocol with an explicit streaming
USTAR/PAX parser. No `tarfile` member cache, vendor execution or extraction of
source links. Selected regular-file copies remain private provisional data until
whole-source validation and owner acceptance. The old failed v1 is untouched.

Only a reviewed, hash-bound execution freeze may run the CLI. The archive is
reused in place; no Dawn download. The two exact PyPI release-JSON requests happen
after the complete scan. Advertised wheel hashes are metadata evidence; wheel
bytes and generator interpreter compatibility remain unverified.

Synthetic regressions:

```sh
PYTHONDONTWRITEBYTECODE=1 build/constrained-decoding-reference/bin/python -B -W error::ResourceWarning -m unittest discover -s scripts/tests -p test_root_dependency_offline_v2.py -v
```

The freeze pins scanner/owner/tests/protocol/reviews, source archive and runtime
files. Python is the retained system 3.9 interpreter, not a newly installed tool.
Selected standard-library/extension bindings are recorded; the system runtime
remains non-hermetic. Every attempt is new; failures retain logs and owner outcome.
Only a complete provisional acceptance file is atomically linked to the final
acceptance name, after the owner outcome and confirmed process-group cleanup.

Limits:300s decision,15s cleanup,2MiB logs,512MiB aggregate writes,1GiB free-space
inspection preflight. The separate18GiB analysis floor is unchanged. No root fixes,
Bazel, SDK builds, wheel installation, devices, Git operations or semantic enablement.
