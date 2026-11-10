# Linker-path validation: static findings v1

2026-11-06. **Best-supported hypothesis: executable-access validation conflicts
with the sandbox's linker-execution denial. Root cause remains UNPROVED.** No
native query, access predicate, flag change, sandbox application or Git mutation
was performed in this investigation.

## Evidence

1. Fresh static inspection found the exact pinned linker: a regular file with
   mode **0755**, 2,331,792 bytes, SHA-256
   `5897b275efd93b201b6df5832dd541262b3f20f290859ba78f2200a6a66ef38b`.
   There are no symlinks in its resolved alias chain. The pinned Clang payload
   and alias identity also match. No missing file, stale payload or broken alias
   was found at inspection; effective access inside Clang was not tested.
2. In explanatory LLVM 21.1.0 source, an absolute `--ld-path` still undergoes
   `can_execute`; failure emits the observed invalid-linker diagnostic and
   returns to program lookup. The Unix predicate checks `R_OK | X_OK` and regular
   file status. General program lookup also tests executability and can return
   the unresolved name. This accounts for the observed error-plus-bare-`ld`
   pattern **in that upstream implementation**. See
   [ToolChain.cpp](https://github.com/llvm/llvm-project/blob/3623fe661ae35c6c80ac221f14d85be76aa870f1/clang/lib/Driver/ToolChain.cpp#L990-L1030),
   [Path.inc](https://github.com/llvm/llvm-project/blob/3623fe661ae35c6c80ac221f14d85be76aa870f1/llvm/lib/Support/Unix/Path.inc#L566-L600)
   and [Driver.cpp](https://github.com/llvm/llvm-project/blob/3623fe661ae35c6c80ac221f14d85be76aa870f1/clang/lib/Driver/Driver.cpp#L6050-L6105).
3. The actual frozen sandbox denies `process-exec` except for pinned Clang;
   `ld` has no execution exception. Apple's locally shipped
   `/usr/share/sandbox/com.apple.smbd.sb`, lines 46–50, links access-right reporting
   to `process-exec` and notes that allowing it also enables execution. Its
   current 2,038-byte source hashes to
   `bbe8bc8edc3ca79bdb7fed334c5e031adcad1fdb888c45bdc454a93b5ddd9824`.
   This corroborates the general interaction; it is not a Clang syscall trace.
4. Published [XNU access checks](https://github.com/apple-oss-distributions/xnu/blob/43a90889846e00bfb5cf1d255cdc0a701a1e05a4/bsd/vfs/vfs_syscalls.c#L6250-L6306)
   consult MAC policy, and the [MAC hook](https://github.com/apple-oss-distributions/xnu/blob/43a90889846e00bfb5cf1d255cdc0a701a1e05a4/security/mac_vfs.c#L867-L889)
   receives executable-access requests. File mode alone therefore does not prove
   an access check succeeds. This reference kernel is not matched to this host.

## Limits and preservation

Neither upstream LLVM nor XNU is a verified source match for Apple's
`clang-2100.1.1.101` or the host's proprietary Seatbelt implementation. Retained
compiler strings are not a call graph. No actual `access` return value, errno or
driver-internal branch was observed. An unsupported option, missing execute bits,
and an execution-policy failure must not be conflated. No separate check-only
Seatbelt operation has been established by this inspection.

The focused audit rehashed **46 selected frozen files** and checked **58 historical
binding entries** across the two attempts' producer/QA locks; entries overlap.
It did not freshly validate all 225 inputs or six directory trees. Its execution
guards recorded zero native/network/access-query events. An initial static audit
stopped at a 1 GiB file-read guard on the broader input set, before publishing a
receipt. That source/failure is preserved; the successful follow-up narrowed the
read set rather than raising the cap. No previously accepted gate was changed.
Host tests were not rerun because application/executor source is unchanged.

## Next step

The [two-case synthetic-target proposal](../planning/P2_CPP_LINKER_EXECUTABLE_ACCESS_DIAGNOSTIC_PROPOSAL_V1.md)
tests whether the actual driver's absolute-path validation is policy-sensitive,
while keeping real `ld` denied in both cases. It is a proposal, not implementation
or execution permission. Another unchanged seven-probe run would add no decisive
evidence. Both old failures remain unchanged; P2 is incomplete and semantic search
stays disabled.
