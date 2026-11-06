# Reusable model capability benchmark plan v1 — design only

2026-11-06. No model acquisition, installation, benchmark execution or capability
claim is authorized here. Follow the configuration identities and capability
matrix in `MODEL_COMPATIBILITY_AND_CAPABILITIES_V1.md` while retaining the existing
conversation/database/runtime ownership. Gemma is one reference, not a universal
template or budget. No existing failed campaign or held-out seal is changed.

## Freeze before measurement

For each configuration bind artifacts, license, runtime/build/ABI/backend/device,
tokenizer, normalization, template, context/output reservation, decoding settings
and resource profile. Verify exact final formatted inputs before inference. Keep
question/source material, complete delivered excerpts, token IDs/counts where
available, schema bytes and packet identities. Bind timing boundaries and native
cleanup evidence independently of terminal outcomes.

Use calibration/development/held-out splits with disjoint source families. Freeze
rubrics, thresholds, candidate policies, selection/tie-break rules and uncertainty
calculations before collection. Review judgments independently. Select on
development only; seal selection before held-out evaluation. Retain failed,
interrupted and timed-out attempts. Do not reopen an exposed held-out set for
tuning or treat an unrun case as a pass. Existing sealed datasets stay sealed;
new work needs a separately versioned protocol.

## Separate measurements

| Layer | Measure | What passing does not establish |
| --- | --- | --- |
| Retrieval | Relevance Recall@5/nDCG@10, language/scope, corpus coverage, exact delivered evidence coverage | Whether a requested detail is actually present |
| Evidence understanding | Full-detail support, missing detail, same-entity/time conflicts, wrong units/numbers and adversarial evidence | JSON/schema or citation correctness |
| Output/citations | Raw schema adherence, supported ID references, exact revision-bound half-open UTF-16 spans/quotes, claim-to-evidence support | Native constraint enforcement or factual support from structural checks alone |
| Runtime/resource | Cold/warm load, first token, decode rate, total latency, available RAM, OS pressure threshold, process PSS/RSS, thermal/battery, Stop and confirmed cleanup | Task accuracy or another backend/device's behavior |

Relevant contradictions must remain discoverable without becoming an unqualified
answer. Measure sufficiency against the text actually delivered within budget,
not a larger source document the model never saw. Include unsupported requests,
formatting errors, Unicode boundaries, malicious source instructions, cancellation
and lifecycle/context invalidation. Preserve exact raw responses; no output
repairs, schema weakening, retries or unconstrained fallback to manufacture passes.

Retain the current P2 point-estimate relevance/no-match gates (Recall@5 >= 0.90,
nDCG@10 >= 0.80 and false-match rate <= 0.05) for protocols to which they apply.
Keep relevance separate from supported-answer recovery, false support and conflict
handling; freeze their numerical criteria in a task protocol before execution.
Diagnostic unfiltered scores do not replace filtered acceptance. Report numerators,
denominators and prespecified descriptive uncertainty, separately by language.

Search keeps its five-second hard deadline and existing performance targets.
The previous 30-second verification experiments do not establish a production
latency target. Record complete user-visible operations, including queueing,
packing, reclaim/load, inference, validation and required cleanup as specified by
the protocol. Report timeout decision and completed cleanup separately; do not
subtract cold loads or omit failures from performance reporting.

## Failure attribution

Every result records the failed stage, first observed cause, evidence and unknowns.
Multiple factors may coexist; do not force an unsupported single diagnosis.

- **Benchmark failure:** incorrect/ambiguous judgments, malformed pack/oracle,
  invalid selection or evaluation arithmetic. Invalidate affected claims; preserve
  attempts and repair the protocol before further evaluation.
- **Integration failure:** wrong template/roles, counting mismatch, transport/schema
  mismatch, wrong artifact/provider, stale context or publication error. Loading
  and API availability are insufficient to exclude these causes.
- **Runtime failure:** native load/decoding fault, unsupported constraints/backend,
  timeout, resource pressure or uncertain release supported by runtime evidence.
- **Model-task failure:** a valid, correctly configured execution on an intact
  benchmark produced an incorrect classification/answer or unsupported claim.
- **Unresolved:** evidence cannot distinguish causes. Repeated whitespace, a bare
  linker name or schema rejection alone must not establish a speculative root cause.

Record collection integrity and task quality as different verdicts. A harness
`OK`, a passing build, or expected fail-closed rejection is not task acceptance.

## Comparing configurations

Plan one reviewed second configuration before claiming interchangeability. Run
each with its own verified tokenizer/template/budget and supported decoding
settings. Freeze a common eligible task set for direct comparison; separately
report tasks excluded by each budget/capability. Never silently clip one model's
evidence or reuse Gemma's proof. Report paired quality, cold/warm resource and
failure outcomes; do not select a winner from repeated held-out attempts.

Host fixtures/tests precede emulator-first integration, then separately authorized
S24 quality/resource sessions. A runtime build or linker-probe pass establishes
neither model interchangeability nor P2 acceptance. No production enablement,
permissions or roadmap scope changes follow automatically from this plan.
