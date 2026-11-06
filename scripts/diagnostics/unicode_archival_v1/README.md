# Explicit historical Unicode verification

The canonical full Python host suite is:

```sh
build/constrained-decoding-reference/bin/python -B scripts/host-python-test-suite.py
```

This route discovers every `scripts/tests/test_*.py` case. It substitutes the ten
historical Unicode test instances with an active copy containing the same ten
methods and assertions. The only permitted byte change is the single call from
`candidate.verify_inputs()` to the explicit archival verifier. Inventory, ordering
and total count must match; missing tests, altered assertions or changed frozen
inputs fail the run. Other modules remain in the discovered suite.

The original test, verifier, snapshots, locks and expected hashes remain unchanged.
Raw historical discovery still executes the original current-source guard and
rejects the changed `AGENTS.md`. That rejection remains covered by a passing
regression; raw discovery is not the migrated full-suite command.

`verify_archive()` runs the verified original guard code in a private read context.
Only logical `AGENTS.md` resolves to the preserved pre-routing guidance snapshot.
All other paths resolve to current source. The new route also pins the authorized
current guidance bytes, so it cannot hide subsequent unauthorized guidance changes.
`verify_current()` and all original native entry points keep their actual root and
strict current-source rejection. No global module root is patched.

Focused checks:

```sh
build/constrained-decoding-reference/bin/python -B -m unittest discover -s scripts/tests -p 'test_unicode_archival.py' -v
build/constrained-decoding-reference/bin/python -B -m unittest discover -s scripts/current-tests -p 'test_unicode_correction.py' -v
```

The manifest binds the old lineage and one explicit archival mapping. This is
read-only historical fixture verification, not permission to run a historical
campaign, native probe or compilation. R2 execution admission remains independent.
No testing dependencies were added.

Use the existing reference environment above: the frozen Unicode module imports
its existing SentencePiece dependency. Bare isolated Python 3.9 without that
package is not a substitute for this environment. An additional bare-interpreter
check is retained as failed setup evidence; no package installation or dependency
stub was used to manufacture a pass.
