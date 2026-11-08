#!/usr/bin/env python3
import hashlib
import os
import struct
import sys
import urllib.request
import zlib
from pathlib import Path

CDN = "https://level3.ssl.blizzard.com/tpr/wow"
INSTALL_DIR = Path(os.environ.get("WOW_DIR", "/Applications/World of Warcraft"))
PLATFORM_TAGS = {"Windows", "arm64"}
PRODUCT = "wowf"


def fetch(kind, key, byte_range=None):
    url = f"{CDN}/{kind}/{key[:2]}/{key[2:4]}/{key}"
    request = urllib.request.Request(url)
    if byte_range:
        offset, size = byte_range
        request.add_header("Range", f"bytes={offset}-{offset + size - 1}")
    with urllib.request.urlopen(request, timeout=120) as r:
        data = r.read()
    if kind == "config" and hashlib.md5(data).hexdigest() != key.lower():
        raise ValueError(f"checksum mismatch for config {key}")
    return data


def locate_in_archives(archives, wanted):
    found = {}
    for archive in archives:
        path = INSTALL_DIR / "Data/indices" / f"{archive}.index"
        data = path.read_bytes() if path.exists() else fetch("data", f"{archive}.index")
        for block in range(0, len(data) // 4096 * 4096, 4096):
            for pos in range(block, block + 4096 - 23, 24):
                ekey = data[pos:pos + 16].hex()
                if ekey in wanted:
                    size, offset = struct.unpack(">II", data[pos + 16:pos + 24])
                    found[ekey] = (archive, offset, size)
        if len(found) == len(wanted):
            break
    return found


def blte_decode(data):
    if data[:4] != b"BLTE":
        raise ValueError("not BLTE")
    header_size = struct.unpack(">I", data[4:8])[0]
    if header_size == 0:
        return decode_chunk(data[8:])
    count = int.from_bytes(data[9:12], "big")
    pos, offset, out = 12, header_size, bytearray()
    for _ in range(count):
        comp_size, _decomp_size = struct.unpack(">II", data[pos:pos + 8])
        pos += 24
        out += decode_chunk(data[offset:offset + comp_size])
        offset += comp_size
    return bytes(out)


def decode_chunk(chunk):
    mode, body = chunk[:1], chunk[1:]
    if mode == b"N":
        return body
    if mode == b"Z":
        return zlib.decompress(body)
    if mode == b"F":
        return blte_decode(body)
    raise ValueError(f"unsupported BLTE chunk mode {mode!r}")


def read_config(text):
    return {k.strip(): v.strip().split() for k, v in
            (line.split("=", 1) for line in text.splitlines() if "=" in line)}


def active_row():
    lines = (INSTALL_DIR / ".build.info").read_text().splitlines()
    header = [h.split("!")[0] for h in lines[0].split("|")]
    for line in lines[1:]:
        row = dict(zip(header, line.split("|")))
        if row.get("Active") == "1" and row.get("Product") == PRODUCT:
            return row
    raise SystemExit(f"no active {PRODUCT} build in .build.info")


def active_build_config():
    row = active_row()
    return row["Build Key"], row["Version"]


def active_cdn_config():
    return active_row()["CDN Key"]


def parse_install(data):
    if data[:2] != b"IN":
        raise ValueError("bad install manifest")
    hash_size = data[3]
    tag_count, entry_count = struct.unpack(">HI", data[4:10])
    mask_len = (entry_count + 7) // 8
    pos, tags = 10, {}
    for _ in range(tag_count):
        end = data.index(b"\0", pos)
        name = data[pos:end].decode()
        pos = end + 3
        tags[name] = data[pos:pos + mask_len]
        pos += mask_len
    entries = []
    for i in range(entry_count):
        end = data.index(b"\0", pos)
        name = data[pos:end].decode()
        pos = end + 1
        ckey = data[pos:pos + hash_size].hex()
        pos += hash_size + 4
        entry_tags = {t for t, m in tags.items() if m[i // 8] & (0x80 >> (i % 8))}
        entries.append((name, ckey, entry_tags))
    return entries


def ckeys_to_ekeys(data, wanted):
    if data[:2] != b"EN":
        raise ValueError("bad encoding file")
    ckey_size, ekey_size = data[3], data[4]
    page_kb, _ = struct.unpack(">HH", data[5:9])
    page_count, _ = struct.unpack(">II", data[9:17])
    espec_size = struct.unpack(">I", data[18:22])[0]
    pages_start = 22 + espec_size + page_count * (ckey_size + 16)
    found = {}
    for p in range(page_count):
        pos = pages_start + p * page_kb * 1024
        end = pos + page_kb * 1024
        while pos < end:
            key_count = data[pos]
            if key_count == 0:
                break
            ckey = data[pos + 6:pos + 6 + ckey_size].hex()
            if ckey in wanted:
                found[ckey] = data[pos + 6 + ckey_size:pos + 6 + ckey_size + ekey_size].hex()
            pos += 6 + ckey_size + key_count * ekey_size
    return found


def main():
    out_dir = Path(sys.argv[1] if len(sys.argv) > 1 else "Wow-ARM64")
    build_key, version = active_build_config()
    print(f"build {version} ({build_key})")
    build = read_config(fetch("config", build_key).decode())
    install = parse_install(blte_decode(fetch("data", build["install"][1])))
    files = {name: ckey for name, ckey, tags in install
             if PLATFORM_TAGS <= tags and "x86_64" not in tags and name.lower().endswith((".exe", ".dll"))}
    if not files:
        raise SystemExit("no Windows arm64 files found in install manifest")
    ekeys = ckeys_to_ekeys(blte_decode(fetch("data", build["encoding"][1])), set(files.values()))
    cdn_config_key = active_cdn_config()
    archives = read_config(fetch("config", cdn_config_key).decode())["archives"]
    locations = locate_in_archives(archives, set(ekeys.values()))
    for name, ckey in files.items():
        ekey = ekeys[ckey]
        if ekey in locations:
            archive, offset, size = locations[ekey]
            content = blte_decode(fetch("data", archive, (offset, size)))
        else:
            content = blte_decode(fetch("data", ekey))
        if hashlib.md5(content).hexdigest() != ckey:
            raise SystemExit(f"checksum mismatch for {name}")
        dest = out_dir / name.replace("\\", "/")
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(content)
        print(f"{name} -> {dest} ({len(content)} bytes)")


if __name__ == "__main__":
    main()
