# Retained repository-setup findings v1

2026-11-02. Read-only trace of the preserved Java-21 attempt. This supplements
the locked failure report; it does not rewrite that report or grant execution.

## Source and stage trace

Root SDK revision: `5e58e9a0aef7abf7091207a8b1d1063a1c800f08`.
All paths below refer to its retained unchanged WORKSPACE or repositories
materialized by the preserved attempt. The preparation manifest binds concrete
source-file SHA-256 and line references; complete graph selection remains unproved.

| Repository | Declaration and completion requirements | Observed unfinished stage |
| --- | --- | --- |
| `rules_ml_toolchain` | SDK WORKSPACE:99–103 pins `2eddbc595cc0bbe650c2640204f66b14f015f1a8`, SHA `3b05687842427041c65d1bc2f4aeda3a3079557120f5be8c34690087a88c5de5`. Original http_archive must download, verify and extract before loading `cc/deps/cc_toolchain_deps.bzl`. | Inventory retained an empty temporary directory. Later cache contains the complete 526,324-byte archive with the correct pinned SHA. The first attempt had not completed archive acquisition; no particular network failure is proved. |
| `llvm-raw` | TensorFlow/XLA `third_party/llvm/workspace.bzl:22–39` pins LLVM `ab547095ead5464dc024d66264d9b8a987f429f3`, SHA `c47662ae375e9523870b87006f27c7f35f686f1489c0cbdf571a583a66c973c9`. tf_http_archive uses the original source archive, strip prefix, BUILD file, six patches and run_lit link. Setup subsequently loads `@llvm-raw//utils/bazel:configure.bzl` to construct the LLVM overlay. | ARM64 temporary transfer has 275,628,032 bytes. It is an exact prefix of the later checksum-verified 279,349,087-byte cached archive, missing 3,721,055 bytes. This particular transfer was incomplete before extraction/patching; LLVM compilation was not reached. |
| Host Rust toolchain | SDK WORKSPACE:277–297 pins rules_rust 0.61.0 (SHA `53c1bac7ec48f7ce48c4c1c6aa006f27515add2aeb05725937224e6e00ec7cea`), calls rust_register_toolchains without a version override. Its `rust/private/common.bzl:34` selects 1.86.0. `rust/repositories.bzl` loads rustc/clippy/cargo, optional rustfmt, then LLVM tools and standard library; `repository_utils.bzl` checks known_shas before extracting. | x86_64 retained 96 regular files plus an unfinished 40,016,824-byte LLVM-tools transfer. That archive does not match pinned SHA `04d3618c686845853585f036e3211eb9e18f2d290f4610a7a78bdc1fcce1ebd9`. No complete accepted LLVM-tools payload existed. This is not evidence of a Rust compile failure. |
| `crate_index` | SDK WORKSPACE:306–338 passes the full Cargo.lock, cargo-bazel-lock.json, Cargo.toml and original llguidance/toktrie annotations. `_crates_repository_impl` locates generator/config/locks, resolves host cargo/rustc paths, then checks repinning and generates BUILD files. The generator URL/SHA come from pinned `crate_universe/private/urls.bzl`. | The retained cargo-bazel binary is 15,762,656 bytes, SHA `a04d79154fabd906d816324314cb71c959458265eef90af18fbfe465b096a1db`; configuration exists but generated repository does not. Host-tool path resolution depends on the incomplete Rust repository. No retained evidence establishes that Cargo metadata or generator execution caused the timeout. |

These observations separate acquisition from extraction/evaluation and generator
execution where supported. They do not reconstruct exact historical thread state,
transfer rates, DNS/TLS failure, or the duration of each internal repository phase.
No alternate repository supplier or toolchain is adopted from these observations.

## Bounded correction

Keep full source/toolchain versions and locks. Stage complete checksum-verified
archives in a new cache; preserve partials and old outputs. Recreate extracted
repositories through the original Bazel rules, including their declared patches,
strip prefixes, canonical identities and tool invocation order. Do not repin,
copy incomplete extracted trees or treat a cache hit as a configured graph.

The complete preparation scope is in `planning/P2_REPOSITORY_CACHE_PREPARATION_V1.md`.
Its manifest also identifies all 37 existing payloads and five missing host/Android
Rust components. The prepared cargo-bazel remains a pinned cache payload; the
preparation tool never runs it. Subsequent Bazel repository evaluation may invoke
the original pinned tools under the existing analysis owner and limits.

No source edits to SDK, dependency versions, Cargo locks, compiler patch, Java
defaults or ABI profiles. Runtime artifacts and emulator acceptance remain
downstream. S24 deferred; semantic search disabled; P2 remains incomplete.
