# Private Bazel server JVM correction v1

Host-only continuation of the reviewed ML Drift analysis. No changes to the
historical runner, SDK, Gradle, global Java selection or device configuration.

`freeze.py` verifies the previous frozen inputs, stages the bundled JDK directly
from the pinned Bazel executable and creates a **new** full inventory/freeze.
Preparation never executes Java or Bazel. Existing destinations reject; failed
preparations must be retained, not silently replaced. The fixed preparation paths
describe this machine; a different preparation needs a separately reviewed freeze.

```sh
python3 scripts/diagnostics/bazel_server_jvm_v1/freeze.py
python3 -m unittest discover -s scripts/tests -p 'test_bazel_server_jvm.py' -v
```

After independent implementation/freeze review, the authorized three-command
analysis uses this entry point and the **reviewed** SHA, never the old Java-17
entry point/freeze:

```sh
python3 scripts/diagnostics/bazel_server_jvm_v1/analyze.py \
  --freeze planning/fixtures/bazel-server-jvm-v1/analysis-freeze.json \
  --freeze-sha256 REVIEWED_SHA \
  --attempt build/bazel-server-jvm-v1/attempts/NEW_ID
```

Exit 1 is input/infrastructure rejection; exit 2 retains analysis evidence that
still needs review and is **not** a qualification pass. Query/cquery only. Keep
the 300-second deadline, shared 15-second cleanup ceiling, 16 MiB log cap and
original repository policy. Full JDK admission repeats before each command.
Configured Java option values and graph labels need explicit interpretation;
server launch success cannot certify SDK compilation settings or ML Drift.

No SDK artifacts, emulator/S24/model work or commits. Semantic search disabled.
