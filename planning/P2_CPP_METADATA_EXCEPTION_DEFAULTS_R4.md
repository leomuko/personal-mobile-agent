# Metadata pre-query exception-default contract — R4

R3 passed independent implementation review but remains unexecuted. Preserve that
review, freeze and its pre-amendment policy/test bytes. No native output has been
observed or used to broaden the policy.

The existing complete-header harness throws std::bad_alloc and uses normal C++
exception/lifetime behavior. Before the first query, explicitly admit and require
the cc1 C++ exception defaults `-fcxx-exceptions` and `-fexceptions`; reject missing,
duplicate or disabled exception modes. The compiler-driver arguments, strict
C++17/C++20 flags,29-command scope, toolchains, dependencies and ceilings do not
change. This corrects an anticipated output-schema omission, not a new compiler
behavior flag or a repair based on a failed native attempt.

All other closed option, library, include, structural-inspector and diagnostic
checks remain unchanged. Any other unrecognized real default still blocks. The
same reviewer must bind PASS to the new exact R4 freeze. No C++ matrix, linker,
produced binary, Bazel, SDK build, root adoption or device operation is authorized.
