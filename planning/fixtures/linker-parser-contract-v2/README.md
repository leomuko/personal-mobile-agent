# Proposed pinned-driver output contract v2

Proposal data only. Nothing here installs a parser, changes the diagnostic
harness, authorizes native execution or changes the historical failed result.

- `contract-proposal.json`: exact ordered frontend/linker grammar, context slots,
  literal values/counts, lexer and qualification limits.
- `mismatch-decisions.json`: individual semantic/reference treatment for every
  inventoried mismatch, including unresolved Apple-specific effects.
- `references.json`: primary explanatory sources and hashed retained SDK files;
  none establishes a source match for the pinned Apple binary.
- `observed-positive.raw.txt`: byte-exact copy of actual raw output. V1 rejected it.
- `synthetic-negative.raw.txt`: constructed test data, not collected evidence.
- `fixture-provenance.json`: construction and native/synthetic labels.
- `mismatch-coverage.json`: complete word inventory and delta coverage.
- `regression-plan.json`: 32 planned families; no implementation/test-pass claim.

The ignored build directory retains initial unsealed draft generators, manual
reference enrichment/provenance, preparation checks and independent proposal QA.
Draft generators are not the approved parser or a claim of regenerating final
editorial/reference decisions. `audit-proposal.py` checks the final proposal's
fixed-witness reconstruction, coverage and historical preservation; it does not
classify arbitrary output under a V2 implementation.

The proposal lock and subsequent QA checkpoint are separate. Historical parsers,
freezes, output and outcomes remain unchanged. Native work requires a future
separately reviewed execution freeze and fresh authorization.
