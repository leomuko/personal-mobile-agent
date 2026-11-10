"""Prepare and pin inert marker data; never test access or execute the marker.

Paths must be absolute, lexically canonical, and symlink-free through ``/``.
The existing root beneath which preparation starts must be owned by the current
uid with mode 0700. Missing directories are created with that same policy.
System ancestors need not belong to the caller, but every ancestor is pinned.
Receipts are JSON-compatible dictionaries; modes contain permission bits.
Failures close descriptors but deliberately retain all preparation artifacts.
"""

from contextlib import ExitStack
import hashlib
import os
import stat


MAX_PAYLOAD_BYTES = 4096


def _require(condition, message):
    if not condition:
        raise ValueError(message)


def _inputs(path, payload):
    _require(type(payload) is bytes, "payload must be bytes")
    _require(len(payload) <= MAX_PAYLOAD_BYTES, "payload exceeds 4 KiB")
    path = os.fspath(path)
    _require(isinstance(path, str), "path must be text")
    _require(path.startswith("/") and "\0" not in path,
             "path must be absolute and contain no NUL")
    parts = path.split("/")[1:]
    _require(parts and all(part not in ("", ".", "..") for part in parts),
             "path aliases and empty components are forbidden")
    return path, parts


def _identity(info):
    return dict(mode=stat.S_IMODE(info.st_mode), uid=info.st_uid,
                gid=info.st_gid, dev=info.st_dev, ino=info.st_ino)


def _file_identity(info):
    return dict(_identity(info), size=info.st_size, nlink=info.st_nlink,
                mtimeNs=info.st_mtime_ns, ctimeNs=info.st_ctime_ns)


def _private(info):
    _require(stat.S_ISDIR(info.st_mode) and info.st_uid == os.getuid()
             and stat.S_IMODE(info.st_mode) == 0o700,
             "existing root and private parents must be owned directories0700")


def _directory(info):
    _require(stat.S_ISDIR(info.st_mode), "parent is not a directory or is a symlink")


def _file(info, mode=0o555):
    _require(stat.S_ISREG(info.st_mode), "marker must be a regular file")
    _require(info.st_uid == os.getuid(), "marker has wrong owner")
    _require(stat.S_IMODE(info.st_mode) == mode, "marker mode changed")
    _require(info.st_nlink == 1, "marker must have a single link")
    _require(0 <= info.st_size <= MAX_PAYLOAD_BYTES, "marker exceeds 4 KiB")


def _check_parents(parents):
    for fd, parent_fd, name, row in parents:
        opened = os.fstat(fd)
        named = os.lstat(name, dir_fd=parent_fd)
        _directory(opened)
        _directory(named)
        _require(_identity(opened) == _identity(named)
                 == {key: row[key] for key in _identity(opened)},
                 "parent identity drift or replacement")


def _parents(stack, parts, create):
    flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC
    fd = os.open("/", flags)
    stack.callback(os.close, fd)
    root = os.fstat(fd)
    _directory(root)
    parents = [(fd, None, "/", dict(path="/", **_identity(root)))]
    _check_parents(parents)
    current = ""
    for name in parts[:-1]:
        try:
            before = os.lstat(name, dir_fd=fd)
        except FileNotFoundError:
            if not create:
                raise
            _check_parents(parents)
            _private(os.fstat(fd))
            os.mkdir(name, mode=0o700, dir_fd=fd)
            before = os.lstat(name, dir_fd=fd)
            _private(before)
        _directory(before)
        child = os.open(name, flags, dir_fd=fd)
        stack.callback(os.close, child)
        opened = os.fstat(child)
        _directory(opened)
        _require(_identity(opened) == _identity(before),
                 "parent replaced while opening")
        current += "/" + name
        parents.append((child, fd, name, dict(path=current, **_identity(opened))))
        fd = child
    _private(os.fstat(fd))
    _check_parents(parents)
    return parents


def _check_entry(fd, parent_fd, name, expected, mode=0o555):
    opened = os.fstat(fd)
    named = os.lstat(name, dir_fd=parent_fd)
    _file(opened, mode)
    _file(named, mode)
    _require(_file_identity(opened) == _file_identity(named) == expected,
             "marker identity drift or replacement")


def _snapshot(fd, parents, name, path, payload):
    _check_parents(parents)
    before = os.fstat(fd)
    _file(before)
    identity = _file_identity(before)
    parent_fd = parents[-1][0]
    _check_entry(fd, parent_fd, name, identity)
    os.lseek(fd, 0, os.SEEK_SET)
    data = bytearray()
    while len(data) <= MAX_PAYLOAD_BYTES:
        chunk = os.read(fd, MAX_PAYLOAD_BYTES + 1 - len(data))
        if not chunk:
            break
        data.extend(chunk)
    _require(bytes(data) == payload, "marker bytes differ from supplied payload")
    _check_entry(fd, parent_fd, name, identity)
    _check_parents(parents)
    _check_entry(fd, parent_fd, name, identity)
    return dict(path=path, sha256=hashlib.sha256(data).hexdigest(),
                **identity, parents=[dict(row) for _, _, _, row in parents])


def prepare(path, payload: bytes):
    """Exclusively create exact data as a single-link file0555, retaining failures.

    Only the new file descriptor is chmodded. Existing paths are never chmodded,
    overwritten, or removed, including when a later validation step fails.
    """
    path, parts = _inputs(path, payload)
    with ExitStack() as stack:
        parents = _parents(stack, parts, create=True)
        parent_fd = parents[-1][0]
        flags = (os.O_RDWR | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW
                 | os.O_NONBLOCK | os.O_CLOEXEC)
        fd = os.open(parts[-1], flags, 0o600, dir_fd=parent_fd)
        stack.callback(os.close, fd)
        initial = os.fstat(fd)
        _file(initial, 0o600)
        _check_entry(fd, parent_fd, parts[-1], _file_identity(initial), 0o600)
        remaining = memoryview(payload)
        while remaining:
            written = os.write(fd, remaining)
            _require(written > 0, "marker write made no progress")
            remaining = remaining[written:]
        _check_parents(parents)
        _check_entry(fd, parent_fd, parts[-1], _file_identity(os.fstat(fd)), 0o600)
        os.fchmod(fd, 0o555)
        os.fsync(fd)
        return _snapshot(fd, parents, parts[-1], path, payload)


def inspect(path, payload: bytes):
    """Read exact bounded bytes with no-follow opens and stable descriptor checks."""
    path, parts = _inputs(path, payload)
    with ExitStack() as stack:
        parents = _parents(stack, parts, create=False)
        parent_fd = parents[-1][0]
        before = os.lstat(parts[-1], dir_fd=parent_fd)
        _file(before)
        flags = os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK | os.O_CLOEXEC
        fd = os.open(parts[-1], flags, dir_fd=parent_fd)
        stack.callback(os.close, fd)
        _check_entry(fd, parent_fd, parts[-1], _file_identity(before))
        return _snapshot(fd, parents, parts[-1], path, payload)


def verify(path, payload: bytes, receipt) -> None:
    """Reject any difference from the complete supplied receipt; never execute."""
    _require(type(receipt) is dict, "receipt must be a dictionary")
    _require(inspect(path, payload) == receipt, "marker or parent receipt drift")
