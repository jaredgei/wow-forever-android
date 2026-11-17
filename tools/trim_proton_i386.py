#!/usr/bin/env python3
"""Removes the 32-bit (i386) Windows files from the bundled Proton package.

WoW Forever runs only the native ARM64 client, so Proton's 32-bit Windows half is never used. It is also what most
antivirus engines flag as a generic heuristic hit (#89), and it is about 50 MB of the APK. This rewrites the .wcp:

- lib/wine/i386-windows keeps its directory entry but loses its files.
- prefixPack.txz loses every 32-bit PE file (machine 0x14c), e.g. those in drive_c/windows/syswow64.

Everything else is copied entry by entry with the same order, permissions and links.

Usage: tools/trim_proton_i386.py <proton.wcp>   (rewritten in place; needs the zstd command-line tool)
"""
import io
import os
import struct
import subprocess
import sys
import tarfile
import tempfile

I386_DIR = "lib/wine/i386-windows/"
IMAGE_FILE_MACHINE_I386 = 0x014C


def is_i386_pe(data: bytes) -> bool:
    if len(data) < 0x40 or data[:2] != b"MZ":
        return False
    offset = struct.unpack_from("<I", data, 0x3C)[0]
    if offset + 6 > len(data) or data[offset:offset + 4] != b"PE\0\0":
        return False
    return struct.unpack_from("<H", data, offset + 4)[0] == IMAGE_FILE_MACHINE_I386


def trim_prefix_pack(data: bytes) -> tuple[bytes, int]:
    removed = 0
    output = io.BytesIO()
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:xz") as source, \
            tarfile.open(fileobj=output, mode="w:xz", format=source.format) as target:
        for member in source:
            payload = source.extractfile(member).read() if member.isfile() else None
            if payload is not None and is_i386_pe(payload):
                removed += 1
                continue
            target.addfile(member, io.BytesIO(payload) if payload is not None else None)
    return output.getvalue(), removed


def main(path: str) -> None:
    raw = subprocess.run(["zstd", "-dc", path], check=True, stdout=subprocess.PIPE).stdout
    removed_files = removed_prefix = 0
    output = io.BytesIO()
    with tarfile.open(fileobj=io.BytesIO(raw), mode="r:") as source, \
            tarfile.open(fileobj=output, mode="w:", format=source.format) as target:
        for member in source:
            name = member.name.lstrip("./")
            if name.startswith(I386_DIR) and not member.isdir():
                removed_files += 1
                continue
            payload = source.extractfile(member).read() if member.isfile() else None
            if name == "prefixPack.txz":
                payload, removed_prefix = trim_prefix_pack(payload)
                member.size = len(payload)
            target.addfile(member, io.BytesIO(payload) if payload is not None else None)
    directory = os.path.dirname(os.path.abspath(path))
    with tempfile.NamedTemporaryFile(dir=directory, delete=False) as temporary:
        temporary.write(output.getvalue())
    try:
        subprocess.run(["zstd", "-q", "-f", "-19", temporary.name, "-o", path], check=True)
        os.chmod(path, 0o644)
    finally:
        os.unlink(temporary.name)
    print(f"Removed {removed_files} files from {I386_DIR} and {removed_prefix} 32-bit PE files from prefixPack.txz")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
