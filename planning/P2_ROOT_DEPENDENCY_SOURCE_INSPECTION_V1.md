# Root-dependency amendment: bounded source inspection v1

Proposal preparation only, 2026-11-02. Review this scope and its exact code/lock
before acquisition. This is not approval to register a repository, apply a build
patch, run Bazel, compile an artifact, access a device or change dependency versions.

The retained LiteRT Dawn macro names `v20260720.160313` but omits a checksum and
the archive is not retained. A complete immutable amendment cannot invent those
bytes or assume its three replacement commands apply. Permit one source-only
inspection attempt to obtain the missing recipe evidence:

1. Resolve **only** `google/dawn` tag `v20260720.160313` through GitHub's public
   Git refs API, with at most two annotated-tag dereferences. Retain every JSON
   response and resolved commit. No authentication, alternate ref or fallback.
2. Fetch only that **immutable commit** archive from codeload.github.com, rather
   than adopting a mutable tag URL. Cap at 256 MiB compressed; retain partials
   and failures. Record SHA-256, bytes, commit and exact strip prefix. The proposal
   must explicitly list this URL/strip-prefix difference from the retained macro.
3. Read the archive as data without extracting a toolchain or executing source.
   Bound uncompressed file bytes to 2 GiB, members to 60,000 and selected text to
   32 MiB. Reject unsafe paths, duplicates and unsupported types. Hash all files;
   retain only generator Python/templates, required generator JSON and licenses
   for static dependency/patch inspection. Never follow archive links.
4. No source execution or generator execution. Compare the three existing
   patch-command literal transformations with actual template preimages in a
   later local static assessment; no SDK/root source change or patch adoption.
5. Read two bounded release-metadata JSON files from PyPI for **Jinja2 3.1.6**
   and **MarkupSafe 3.0.3**, the versions already declared by the root custom pip
   repositories. Retain hashes/URLs for the pure-Python Jinja2 wheel and ordinary
   CPython-3.13 macOS ARM64 MarkupSafe wheel. Do not download/install wheels or
   change existing pip providers. The amendment must account for the existing
   custom pip repository's system `python3` versus the generator's still-to-be-
   confirmed hermetic Python selection; aliases alone cannot certify wheel ABI.

One owned Python worker: **300-second whole inspection decision**, **15-second
cleanup ceiling**, **2 MiB combined logs**. Metadata responses are capped at
1 MiB each (at most three Git responses plus two PyPI responses), transport reads at 15 seconds, and total output reservation at
512 MiB. Require 1 GiB free before starting. Use the existing reviewed process
owner and a fresh exclusive attempt. A failure stops this inspection; no retries,
new versions, opaque replacement, expanded limits or automatic data deletion.

Retained amendment sources and the inspector/Python interpreter are hash-bound
before this step. Retain all response bodies, partial archive, per-file manifest,
worker outcome and code identities. Review source evidence before sealing the
full compatibility amendment. Missing bytes, absent patch preimages or unknown
transitive dependencies block adoption; an inspection PASS cannot certify
configured/compiled API or numerical compatibility.

Current storage is about 2.7 GiB. This small bounded inspection does not authorize
another analysis attempt: preserve the existing **18 GiB** analysis preflight,
300-second commands, 15-second cleanup and 16 MiB logs. Capacity for fresh root/
ABI trees must be resolved separately, preserving local evidence.
