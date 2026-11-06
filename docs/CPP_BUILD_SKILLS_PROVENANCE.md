# C++ build skills provenance v1

Installed and verified 2026-11-06 through the existing `skill-installer` helper,
using `--method download` and the user-specified immutable revisions. Neither
destination existed; no different installation was overwritten. Upstream skill
contents remain unchanged. These are machine-local development guidance, not
application dependencies, runtime packages or native-execution authorization.

| Skill | Repository / revision | Installed path | Entry-point SHA-256 |
| --- | --- | --- | --- |
| Bazel Evidence Engineering | `Kingda8/Agent-skills-for-Bazel@1fdaa839a030421d5e69709bde1bfd72f773b54e` | `/Users/kezileo/.codex/skills/bazel-evidence-engineering` | `854cbbdd2242e973a21ef07c54b087a10f69eaa79a717b2bb709106cc207034e` |
| Clang | `mohitmishra786/low-level-dev-skills@bdc58472fa9f309ed1b3f7d985a0d8e9bd8f4608` | `/Users/kezileo/.codex/skills/clang` | `0e6fcb1d4c4a0eea87a82c587f9644352f3ed809d75aaed0966eaa65372b1057` |

Both supplied entry hashes match. All **19 installed files** were compared
byte-for-byte with their exact pinned upstream paths. The JSON companion records
every file, size, hash, URL, reviewed resource and license identity. No installed
script was executed. Only documentation, the Bazel license and its agent metadata
descriptor were copied by the installer; no hook/plugin/MCP/runtime installer ran.
Configuration hashes and plugin-directory names remained unchanged; exactly the
two requested skill directories were added. Existing diagnostic-skill provenance
and installed skill contents remain unchanged.

## Licenses and reviewed resources

Bazel's included `LICENSE.txt` remains installed unchanged. Its MIT license is
also retained at `docs/third_party/CPP_BUILD_SKILLS/bazel-evidence-engineering-LICENSE.txt`.
Clang's repository-root MIT `LICENSE` was acquired from the same pinned revision
and retained at `docs/third_party/CPP_BUILD_SKILLS/clang-LICENSE.txt`; no extra file
was inserted into the upstream skill directory.

Read completely: both `SKILL.md` entry points; Bazel's repository-first inspection,
platforms/hermeticity, testing/evidence, artifacts/supply-chain, current-ecosystem
and external-dependency references; and both Clang flags and clang-tidy references.
The Bazel license was also read. Other installed references are hash-inventoried,
not falsely claimed as reviewed. Cross-references to other skills do not authorize
their installation or any command shown in an example.

## Routing and exclusions

- Use Bazel guidance for SDK dependencies, configured graphs, toolchains, actions
  and artifact provenance. **Pinned Bazel 7.6.1** and its WORKSPACE/toolchain
  contracts govern; the skill's Bazel 9 examples are not upgrades or new flags.
- Use Clang guidance for targeted diagnostics, template errors and toolchain
  investigation. Verify suggested flags against the pinned Apple compiler. The
  already reviewed seven-probe argument recipe is unchanged.
- Do not automatically run clang-tidy fixes, modernize code, change warning policy,
  enable sanitizers/LTO, substitute a linker, add aliasing/vectorization assumptions
  or weaken frozen acceptance. Existing project instructions and protocols govern.

## Frozen guidance history

Inspection found AGENTS bindings in older analysis freezes and QA snapshots, but
not the current linker proposal or rejected executor R1 input lists. The pre-change
AGENTS bytes and their SHA are retained in
`planning/fixtures/cpp-build-skills-v1/AGENTS.before.txt`; the adjacent inventory
records historical binding locations. Old snapshots/freezes were not refreshed.
They remain historical and refer to preserved old guidance. Their current-source
admission may correctly reject the authorized routing change.

Only the new executor freeze will bind the amended AGENTS, this provenance, both
licenses and installed guidance identities. Installation does not qualify native
linker selection. The retained QA agent's usage limit currently prevents fresh
independent executor approval; a new genuine review and canonical admission proof
remain necessary before separately authorized native execution.

No full application test rerun is required for installing documentation. Executor
code changes receive their own focused/full host checks. No Bazel invocation,
compiler probe, model acquisition, device workload or Git commit follows from
installation. The skills become automatically available on the next turn and can
be read directly during this task. P2 remains incomplete; semantic search is disabled.
