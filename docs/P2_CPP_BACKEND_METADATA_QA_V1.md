# Metadata-2 results — independent QA checkpoint

**PASS for evidence integrity and reporting; native qualification remains BLOCKED.**
The authorized R2 run completed with 18 passing queries, one output-validation
failure and 12 queries NOT_RUN. Cleanup was confirmed, no acceptance was published
and no automatic retry occurred. P2 is incomplete; semantic search is disabled.

The sole retained independent reviewer replayed all 19 saved native command outputs
without launching tools. All 16 inspector checks, Clang version and resource-dir
checks passed. The linker-path command exited 0 but returned exactly `ld\n`;
its validator requires the pinned absolute linker identity and correctly rejected
that output. The cause of the bare name remains unproved. Existing pinned linker
bytes do not establish which linker that name denotes. No ambient PATH assumption,
output repair or weaker selection contract is accepted by this result.

Fresh QA verified all 133 frozen inputs, including OS-cache payloads, six complete
trees, eight native identities, four graph contexts, all 13 result-lock bindings
and preservation of metadata-1 and the R1 admission-rejection evidence. It found
no new integrity defects. Eight execution boundaries were instrumented and none
was called during review. Earlier preparation test counts (343 producer host and
52 independent affected tests) remain historical; no test suite or native query was
rerun for this unchanged-source results audit.

The parent records 31.237136 seconds before terminal serialization; the worker-owner
measurement including cleanup is 16.4490345 seconds. Neither is an isolated cleanup
duration. Cleanup is explicitly CONFIRMED despite the generic disjunctive outer
error text. The raw failed-query native duration is 0.011715875 seconds; this is not
the full guarded query duration. These distinct fields remain unchanged.

The frozen producer report retains its earlier pending-review narrative. The
adjacent `metadata-2-results-review.json`, reviewed snapshot and checkpoint provide
this subsequent verdict without rewriting prior evidence.

Next: separately assess the linker lookup/identity contract using this witness,
then review any changed query or policy before native execution. This checkpoint
authorizes no further execution, sandbox widening, remaining-query continuation,
C++ case, root adoption, SDK/Bazel build or device workload. All 72 complete-header
C++ cases remain NOT_RUN. The 18 GiB analysis floor, emulator-first order and S24
deferral are unchanged. No Git mutation occurred.
