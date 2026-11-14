#!/usr/bin/env python3
"""Disposable Wine integration: synthetic token, loopback server, dummy child.

Usage: python3 test_integration.py --wine /path/to/wine64 --compiler /path/to/mingw-gcc
Set WINESERVER/LD_LIBRARY_PATH externally when using a portable Wine distribution.
This exercises the actual PE/DPAPI transport; it does not log into Battle.net.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import socket
import struct
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent


def windows(path):
    return "Z:" + str(path.resolve()).replace("/", "\\")


def read_exact(peer, length):
    output = bytearray()
    while len(output) < length:
        chunk = peer.recv(length - len(output))
        if not chunk:
            raise EOFError("Bridge closed its socket")
        output.extend(chunk)
    return bytes(output)


def read_frame(peer):
    length = struct.unpack("!I", read_exact(peer, 4))[0]
    assert 0 < length <= 4096, length
    return read_exact(peer, length)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--wine", required=True)
    parser.add_argument("--compiler", required=True)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="wow-bridge-integration-") as tmp:
        root = Path(tmp)
        env = dict(os.environ, WINEPREFIX=str(root / "prefix"), WINEDEBUG="-all")
        bridge, child = root / "bridge.exe", root / "child.exe"
        subprocess.run([str(HERE / "build.sh"), args.compiler, str(bridge)], check=True)
        subprocess.run([args.compiler, "-std=c11", "-Os", "-municode", "-static",
                        str(HERE / "test_child.c"), "-lcrypt32", "-ladvapi32", "-o", str(child)], check=True)
        # Initialize the disposable prefix before the listener's bounded accept.
        init = subprocess.run([args.wine, str(bridge)], env=env, capture_output=True, timeout=90)
        assert init.returncode == 2, (init.returncode, init.stderr.decode(errors="replace"))

        def attempt(name, token=b"synthetic-unit-test-token", bad_nonce=False, login_file=False):
            config, marker = root / (name + ".cfg"), root / (name + ".marker")
            nonce = secrets.token_bytes(32)
            stale = root / "login.txt"
            if login_file:
                stale.write_text("synthetic-placeholder\n", encoding="ascii")
            with socket.socket() as listener:
                listener.bind(("127.0.0.1", 0))
                listener.listen(1)
                listener.settimeout(30)
                config.write_text(f"{listener.getsockname()[1]}\n{nonce.hex()}\n", encoding="ascii")
                config.chmod(0o600)
                process = subprocess.Popen([args.wine, str(bridge), windows(config), windows(child),
                                            "--validate", windows(marker)], env=env,
                                           stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                try:
                    with listener.accept()[0] as peer:
                        peer.settimeout(30)
                        client_proof = hashlib.sha256(b"WOW-SESSION-CLIENT-1" + nonce).digest()
                        server_proof = hashlib.sha256(b"WOW-SESSION-SERVER-1" + nonce).digest()
                        assert read_exact(peer, 32) == client_proof
                        assert not config.exists(), "Bootstrap file was not deleted"
                        # Reflecting the client proof must not authenticate the server.
                        peer.sendall(client_proof if bad_nonce else server_proof)
                        if bad_nonce:
                            assert peer.recv(1) == b""
                            status = b"BAD_NONCE"
                        else:
                            device = json.loads(read_frame(peer))
                            assert len(device) == 23 and all(isinstance(v, int) for v in device.values())
                            peer.sendall(struct.pack("!I", len(token)) + token)
                            status = read_frame(peer)
                            if status == b"STARTED":
                                assert process.poll() is None, "Helper did not wait for child"
                                marker.write_text("release", encoding="ascii")
                    stdout, stderr = process.communicate(timeout=30)
                    assert not stdout, "Helper emitted stdout"
                    if status == b"STARTED":
                        assert process.returncode == 0, (process.returncode, stderr)
                        assert marker.read_text() == "OK", "Child could not decrypt/validate registry token"
                    else:
                        assert process.returncode != 0
                        assert not marker.exists(), "Failed handoff launched child"
                    return status
                finally:
                    if process.poll() is None:
                        process.kill()
                        process.communicate()
                    stale.unlink(missing_ok=True)

        assert attempt("nonce", bad_nonce=True) == b"BAD_NONCE"
        assert attempt("oversized", token=b"a" * 2049) == b"ERROR:8"
        assert attempt("invalid_ascii", token=b"test\0token") == b"ERROR:8"
        assert attempt("stale_file", login_file=True) == b"ERROR:9"
        assert attempt("success") == b"STARTED"
        clean_marker = root / "clean.marker"
        probe = subprocess.run([args.wine, str(child), "--probe-clean", windows(clean_marker)],
                               env=env, capture_output=True, timeout=30)
        assert probe.returncode == 0 and clean_marker.read_text() == "OK", probe.stderr
        malformed = root / "malformed.cfg"
        malformed.write_text("12345\ninvalid\n", encoding="ascii")
        result = subprocess.run([args.wine, str(bridge), windows(malformed), windows(child)],
                                env=env, capture_output=True, timeout=30)
        assert result.returncode == 2 and not malformed.exists()
        print("Wine bridge integration passed: nonce, frame bounds, login.txt refusal, DPAPI, child lifetime, cleanup")


if __name__ == "__main__":
    main()
