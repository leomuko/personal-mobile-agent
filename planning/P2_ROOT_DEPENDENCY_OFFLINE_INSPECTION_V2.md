# Offline Dawn source inspection v2 — finalized protocol

2026-11-02. Proposed for prerequisite review before implementation. This supersedes
the *proposal's algorithm description*, not the preserved v1 attempt or any old
lock. The current task authorizes implementation after this protocol passes
independent review, and execution after implementation/freeze review. No root
fixes, Bazel, generator, wheels, SDK builds, devices or commits are authorized.

## Exact input and scope

Reuse only the retained 28,737,866-byte `dawn-source.tar.gz.part`, SHA-256
`4ee2687c6f4b2b1f30e70028763201c898a763989f7aafcb284e197ab0a4735b`,
commit `466163dc7bf94146ac9a921b86cc3ed08184ebb8`. No Dawn download or tag
resolution. Verify size/hash before and after scanning the same open descriptor;
require unchanged descriptor metadata and path identity before publication.
Do not follow input symlinks. Keep all subtrees; a cap/error fails the attempt.

Only after a complete source scan, permit exactly these two HTTPS metadata GETs:
`https://pypi.org/pypi/Jinja2/3.1.6/json` and
`https://pypi.org/pypi/MarkupSafe/3.0.3/json`. Reject all redirects, including same-URL redirects.
No retries. Identify uniquely the non-yanked wheels
`jinja2-3.1.6-py3-none-any.whl` and
`markupsafe-3.0.3-cp313-cp313-macosx_11_0_arm64.whl`; validate package/version,
filename, positive size, SHA-256 and HTTPS `files.pythonhosted.org` artifact URL.
Record response-byte hashes separately from *advertised wheel* hashes. Do not
download, install or execute wheels. This does not prove interpreter compatibility.

## Parser and validated content

Use a small explicit sequential 512-byte USTAR/PAX reader over Python gzip, not
`tarfile`'s accumulating member cache. Support ordinary files, directories,
relative symlinks, in-prefix hardlinks and bounded PAX global/local headers.
Reject GNU long/sparse/special/unknown types rather than guessing their meaning.
Validate unsigned header checksum, octal numeric fields, USTAR magic, exact reads,
nonnegative bounded sizes, payload/padding and two zero end blocks. Require all
remaining decoded bytes to be zero, consuming gzip EOF/CRC. A nonzero trailing
archive or truncated/invalid gzip fails. Header/extension records all count against
the header ceiling, including the root directory; there are no excluded subtrees.

PAX records use decimal byte lengths including the length prefix and newline.
Require exact consumption, unique UTF-8 keys, no malformed length, and at most
16 keys. Permit only `comment,path,linkpath,size,mtime,atime,ctime,uid,gid,uname,gname,hdrcharset`;
reject unknown fields and non-UTF-8 encoding declarations. Global path/link/size
overrides are forbidden. Apply local values once to the following ordinary member;
an unused local header or chained local header fails. Header hashes preserve all
otherwise uninterpreted source metadata. PAX extensions have metadata records;
they cannot silently disappear from the inventory digest.

Reserved PAX semantics are frozen as follows. Empty values/deletions reject for
every allowed key. `size` accepts only ASCII `0|[1-9][0-9]*`, at most ten digits
and at most 2 GiB; it may override only an ordinary regular file. `linkpath` may
override only a symlink/hardlink. All directories/links have zero raw and effective
payload sizes. `path` may override any ordinary member, including root only if it
remains the exact directory prefix. Validate effective paths and link targets
after applying overrides. `hdrcharset` accepts only exact `UTF-8`; `BINARY` and
other spellings reject. `uid/gid` use the same unsigned decimal grammar, bounded
to 2^32-1. `mtime/atime/ctime` accept ASCII `-?(0|[1-9][0-9]*)(\.[0-9]{1,9})?`,
at most 32 bytes; they are metadata only, never filesystem times. Other permitted
values are nonempty UTF-8 without NUL/control bytes, subject to the frozen limits.
Global values persist, with later global values replacing matching prior keys;
local values override those globals once, then are cleared. A global header while
a local header is pending rejects. Applying an incompatible reserved override
rejects rather than ignoring it. No GNU extension semantics are inferred.

Effective member paths must be valid UTF-8, canonical relative POSIX paths beneath
the exact `dawn-<commit>` prefix. Reject NUL/control characters, backslashes,
empty/dot/dot-dot components, absolute paths and escaping links. Directories may
have one trailing slash removed. Hardlinks must name the same archive prefix;
symlink resolution must remain in that prefix. Links are recorded, never created
or followed. Only regular `generator/**`, the two required Dawn JSON files, and
files whose basename starts `LICENSE` are copied as non-executable data. A
selected file beneath any link must not be accepted; reject such layouts.
Require the generator/template/JSON/license inputs listed by the manifest.
Selected writes before end-of-scan are private provisional staging only. Track
source non-directories (files and links) with the fixed kind bitset below. After scanning every header,
look up every selected path's ancestors, including root, in the duplicate table;
any non-directory parent or exhausted lookup rejects before source receipt or
acceptance publication. This detects links appearing before or after descendants.
No source link is created on disk, even during staging.

## Exact inventory encoding

Inventory digest starts with UTF-8 bytes `edge-dawn-inventory-v2\n` (actual LF).
For every nonzero tar header in archive order, append one frame: unsigned 32-bit
big-endian payload byte length followed by canonical JSON UTF-8 bytes. JSON uses
`sort_keys=True,ensure_ascii=False,allow_nan=False,separators=(',',':')` and no LF.
All records have exactly these keys:

`headerSha256,kind,path,link,size,mode,contentSha256`.

`kind` is `file,directory,symlink,hardlink,pax-global,pax-local`. `path` is the
effective prefix-stripped ordinary member path (`""` for root), or the raw bounded
extension-header path for PAX. `link` is the original effective link text or `""`.
`size` is the effective ordinary payload size or raw PAX payload size; `mode` is
the numeric original header mode. `contentSha256` hashes regular-file or raw PAX
payload bytes, and is JSON null for directories/links. `headerSha256` hashes the
exact original 512-byte header. No timestamps, host paths or execution timings
enter this digest. Inventory order and header bytes intentionally affect identity.
Receipt includes digest, header/member/type counts, file/decoded/metadata byte
counts and selected-file records. It does not retain every inventory record.

## Frozen resource amendment

These changes require explicit prerequisite review; no run adjusts a cap:

| Resource | V2 maximum |
| --- | ---: |
| Headers, including PAX and root | 300,000 (v1 ordinary-member cap was 60,000) |
| Duplicate key table | 1,048,576 slots × 32 bytes + 131,072 occupancy bytes + 131,072 non-directory-kind bytes = 33,816,576 bytes |
| Per insertion probing | 4,096 slots; exhaustion rejects |
| Effective path or link UTF-8 | 1,024 bytes each |
| Aggregate effective path/link bytes | 128 MiB (replaces v1's 4 MiB name accounting) |
| Framed inventory bytes hashed | 256 MiB, including digest prefix |
| Total metadata hash inputs | 768 MiB, including raw headers, PAX, framed records and every duplicate/ancestry name lookup |
| One PAX payload / aggregate PAX payloads | 64 KiB / 8 MiB |
| PAX keys / key bytes / value bytes | 16 / 32 / 1,024 |
| Active parser state | 2 PAX maps, one 512-byte header, one ≤1 MiB payload block; no member list |
| Decoded gzip bytes, including padding/trailer payload | 2 GiB |
| Selected data / selected records | 32 MiB / 4,096 |
| Source receipt / owner outcome or acceptance record | 32 MiB / 64 KiB each |
| Each package JSON / all package JSON | 1 MiB / 2 MiB |
| Worker peak RSS | 256 MiB; check every 1,024 headers and before publication |
| Aggregate attempt writes, including selected data, metadata, receipts, logs/outcome | 512 MiB |
| Worker decision / cleanup / combined logs | 300 s / 15 s / 2 MiB |
| Free space before inspection | 1 GiB; separate from unchanged 18 GiB analysis floor |

Duplicate detection hashes each effective ordinary path as SHA-256 and uses a
fixed open-addressed byte table with occupancy and non-directory-kind bitsets. Insertions
and ancestry lookups both stop and reject after 4,096 probes. Equality rejects even
if a theoretical distinct-name digest collision occurred. PAX records do not
enter this table; ordinary root does. Never keep all names, TarInfo objects,
payloads or inventory JSON frames. Store selected file records only, capped
independently. Reserve logs plus two 64 KiB owner files before worker writes.
Check write budgets before writes. Receipt serialization must be incremental and
bounded, not `json.dumps` of an unbounded object. Gzip's library buffers and OS RSS
are included in the peak-RSS check; the parser itself allocates no hidden member
cache. Runtime/module identities and Python/zlib versions are frozen, with the
system runtime's non-hermetic limitation recorded explicitly.

## Ownership, publication and evidence

Use the retained reviewed process owner under a new scanner identity. New attempt
directory only. Persist interruption/spawn/deadline/output/cleanup failure outcomes;
unknown cleanup is never confirmed. No fallback or retry. Worker source receipt
and package metadata are provisional evidence. Only the owner may publish the
small `accepted-receipt.json`, after exit-zero, confirmed cleanup, exact receipt
hashes and input identity recheck. Any failure leaves evidence but no acceptance.
If metadata fails after source validation, record those distinct outcomes rather
than claiming a complete Python recipe. Preserve every historical frozen byte.

Focused regressions cover unsafe/duplicate paths and links, malformed PAX/headers,
cap boundaries, fixed duplicate-table storage/probe exhaustion, bounded parser
state, digest replay/ordering/content changes, truncation/CRC/trailing data,
input replacement, write bounds, interruption and failed cleanup; metadata cannot
be requested before source validation. Then review implementation and exact freeze
before one real execution. Independent results review verifies complete source
coverage and qualified versus merely advertised identities.

Afterward still pending: four-site patch semantics, actual wheel-byte verification,
generator Python/toolchain compatibility, generated headers, FP16 numerical/Android
checks, root guards and full configured graphs. P2 incomplete; emulator downstream,
S24 deferred, semantic search disabled.
