# Dawn callback patch: static assessment v1

This is a source-level assessment, not callback execution or semantic approval.
The qualified template SHA-256 is
`e45fcbe135a35310238df2f8ac310b295e6c8d5d5c7933895dab90340b6e799a`.
The three complete programs and six operations are retained in
`planning/fixtures/root-dependency-offline-v2/patch-manifest.json`.
Nothing here adopts the root patches or changes that historical manifest.

| Program | Intended change | What remains unproved |
| --- | --- | --- |
| 1 | Keep `<span>` under C++20; under older language modes include the real pinned Abseil header and alias `std::span<T>` to `absl::Span<T>`. | Constructor/conversion/mutability behavior, consumed API compatibility and portability of adding an alias in `std`. This is not established by matching text. |
| 2 | Introduce `take_tuple<N, tuple<Args...>, index_sequence<Is...>>` and use its `type` for the callback's leading argument tuple. Replace exactly four C++20 lambda/type-identity expressions. | Actual template instantiation, diagnostic behavior, typed/untyped callbacks, empty/one-argument boundaries and header compilation. |
| 3 | Replace the mode-member `requires` expression with `has_mode`/`void_t`. Replace the explicit-userdata overload's constraint with defaulted-template SFINAE. | Callback selection, access/substitution boundaries, capture exclusion, argument/userdata forwarding, allocation/destruction and the effect of the added template parameters. |

## Four trait sites

The four program-2 sites are the raw pointer, noexcept raw pointer, const member
pointer (lambda operator), and noexcept const member pointer specializations.
The intended leading-tuple length remains `NumCppArgs` for `Untyped`, otherwise
`NumCppArgs - 1`. The change is intended to preserve that indexing and the existing
return/pointer/capture definitions. Source reconstruction and postimage hashes
establish which bytes change, not equivalence for every instantiation.

The future harness must exercise all four, with typed and untyped arguments,
capturing and noncapturing callbacks, plus the expected capture-rejection cases.
It must use complete generated ordinary and wire-client headers. Explicitly
supplied template parameters and substitution/access edge cases need assessment
before any broad API-equivalence claim; the added parameters must not be assumed
to be invisible merely because their defaults reproduce the intended case.

## Blocking prerequisites and claims

The Python/generation profile can establish that the three exact programs produce
the fixed final template and corresponding ordinary/wire generated postimages.
It cannot prove callback behavior. The exact root-selected Abseil source is absent
from checked retained locations, and its acquisition is outside the current scope.
All C++ compilation/execution remains blocked. The unchanged `explicit(false)`
construct must also pass the future strict C++17 test; it cannot be removed or
accepted as an extension to manufacture compatibility.

A future reviewed scope must close real Abseil/SDK/compiler prerequisites, compare
positive runtime behavior, and validate expected negative diagnostics after
successful full-header compilation. Android/FP16/configured graphs remain separate.
