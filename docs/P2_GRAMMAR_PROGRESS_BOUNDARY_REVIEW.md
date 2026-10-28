# Grammar Progress: Diagnostic Boundary Review

2026-10-28. Applied the reviewed `rust-c-ffi-safety` and
`bash-defensive-patterns` guidance plus existing Python-testing guidance.
Scope is the standalone diagnostic, not the app's native runtime.

## Concrete Findings Before Candidate Work

1. **P2, diagnostic process boundary: blocking writes bypassed the watchdog.**
   `run_probe()` originally used blocking stdin writes while checking its
   deadline only around reads. A synthetic child that emitted a tokenizer
   request then stopped reading held the broker past its configured 200 ms
   deadline; an independent supervisor still found it blocked at 1000 ms.
   The reproducer killed/reaped only its own processes. This was not a model
   run, and does not revise historical native cleanup evidence.
2. **P2, diagnostic callback boundary: broken output aborted through the C ABI.**
   The original callback used `println!`/flush `unwrap`. With stdout's reader
   closed, the retained original executable exited via SIGABRT (-6), rather
   than a controlled callback-failure exit. The corrected executable exits 3
   with no panic output. Both are failed diagnostic executions, never accepted
   model outputs. Core dumps were disabled for the owned reproducer children.

Corrections are narrow: deadline-aware nonblocking writes; a typed signal
interruption that is not swallowed as selector EINTR; owned-child termination
before failure publication; bounded callback transcript retention; and
fallible callback writes/panic containment. No process-wide package stop,
toolchain/feature change, sampling change or application integration was added.

Original source copies and reproduction evidence are retained under ignored
`build/grammar-progress-v1/review-baseline/` and adjacent JSON/stderr files.
The original prepared workspace and host/emulator reports under
`build/llguidance-mask-replay/` were not rewritten. Corrected code is copied to
the new `build/grammar-progress-v1-final/` workspace, with a new preparation
manifest and source/binary identities. Previous evidence remains historical.

Independent QA then found two additional gaps in the first reviewed snapshot:
setup interruption after child creation was outside protected cleanup (P2), and
Python equality admitted incorrectly typed numeric metadata (P3). Both are
corrected in the follow-up snapshot. Signal ownership and the protected region
now precede child construction; a signal during synchronous spawn is recorded
until its returned child handle is tracked, then causes owned cleanup before
any configuration is sent. Deterministic setup- and spawn-window regressions
confirm reaping and failure evidence. EOS/vocabulary fields and each stop ID now
require actual integers, rejecting booleans and equal-valued floats before
subprocess admission. The original QA FAIL and candidate failures are preserved.

## FFI Contract Checked

- **Nullability/alignment/length:** empty callback size queries accept null
  pointers without making a slice. Nonempty input requires non-null storage;
  output capacity requires a non-null aligned `u32` buffer and a representable
  byte extent. Writes use only `i < cap` and report the full required token
  count, preserving the pinned caller's resize/retry convention. Returned
  constraint handles, masks and commit buffers are checked before use.
- **Provenance/liveness:** null/alignment checks cannot prove allocation size,
  aliasing or liveness. Those remain explicit obligations of the exact pinned
  LLGuidance producer/caller, not claims of protection against arbitrary hostile
  C pointers. No pointer is constructed from a numeric handle or retained after
  its documented lifetime. Known-bad pointer tests reject without dereferencing.
- **Layout/scalars:** types come from the same pinned Rust crate as the C ABI
  producer, rather than hand-copied bindings. Its initialized Rust booleans
  supply valid boolean values; arbitrary C bit patterns are not supported.
  An independent C compilation of unchanged `llguidance.h` confirms 64-bit
  sizes/offsets, matched by Rust tests: mask size/alignment/stop offset 16/8/12,
  commit size/count offset 16/8, constraint-init size 80, tokenizer-init size 72.
  The platform's `c_char` is retained for the error buffer.
- **Initialization:** FFI output structures start as valid initialized values;
  success is checked before consuming returned buffers. The initialization
  struct's zero/null fields are valid, then the pinned defaults setter writes
  the actual configuration. No uninitialized reference is manufactured.
- **Ownership/deallocation:** vocabulary/lens storage remains live through
  tokenizer destruction. A constraint is freed through `llg_free_constraint`
  before `llg_free_tokenizer`; neither buffer is freed by Rust as a foreign
  allocation. Normal errors traverse the same cleanup closures. Mask/commit
  pointers are borrowed only until the next LLGuidance call; full masks are
  copied while valid. Error strings become owned text before handles close.
- **Callbacks/concurrency:** the callback function is static, user data is null,
  and the broker exchange is serialized. Callback buffers are used only during
  that call; input and output are disjoint under the pinned contract. Ordinary
  callback errors and unwinding panics cannot return through the C ABI: the
  standalone process fails closed. An allocation abort or corrupt native
  producer is not made recoverable by `catch_unwind`.
- **Resource bounds:** vocabulary reading is bounded before allocation grows
  past its 16 MiB framing cap, including one excess-byte detector. Protocol
  lines, prefix/checkpoint counts, callback values/count and transcript bytes
  are bounded. A callback failure cannot produce a successful result record.

## Python And Bash Ownership

The broker operates in its main thread so signal handlers have a defined owner.
SIGINT/SIGTERM become typed interruption errors, then only the owned child's
process group is stopped and reaped. Previous handlers are restored. A dedicated
regression confirms SIGTERM leaves a retained failure and no live owned native
child. Unrecoverable host termination such as SIGKILL cannot run Python cleanup;
this is not an unattended model-supervision guarantee.

The new host-only verification wrapper quotes paths, enables strict/error modes,
validates its two arguments/toolchain, refuses to replace a report directory,
uses no background jobs and records its exit status. The Python broker owns
native children. There is no package-wide cancellation, automatic retry, stale
proof reuse or evidence deletion. Existing unrelated QA scripts were inspected
as context and not broadly rewritten.

## Verification Limits

Rust unit checks cover good/empty callback buffers, null/misaligned buffers,
broken-pipe error propagation and the pinned C layout. Python regressions cover
backpressure, callback failure, invalid bytes, signal cleanup, identity tampering
and existing ownership/error paths. The pinned Rust 1.87 toolchain and original
dependency features are retained; default formatting/Clippy checks do not adopt
`rust-hardening`'s separate mandatory policy.

Prepared EOS, stop-token order, vocabulary size and model-free scope are also
checked against the frozen host contract before any diagnostic subprocess.
Tampering regressions verify rejection at that admission boundary.

Nightly Miri/sanitizers and ShellCheck were not run: the requested toolchain is
pinned stable and ShellCheck is not installed. No complete UB-freedom claim or
arbitrary-C compatibility claim follows. No S24, emulator, Gemma, SDK/JNI or GPU
execution is authorized or performed by this review.
