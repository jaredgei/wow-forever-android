# Wine session bridge

`wow-session-bridge.exe <private-config> <game-executable> [game-arguments...]`

The helper runs inside the game's Wine prefix, obtains its Windows device
fingerprint, receives a fresh game session credential from the Android launcher,
protects it with that prefix's DPAPI, and starts the game. It keeps running until
the game exits so the existing Wine process lifecycle still tracks the game.

This is an experimental authentication path. A successful cross-build and the
portable tests do not establish that a particular Android/Wine configuration can
authenticate or run the game.

## Local protocol

The private bootstrap file contains an ASCII decimal port, LF, a 64-character hex
nonce, and LF. It is opened exclusively and deleted when read. The helper only
connects to `127.0.0.1`; no remote host is accepted. The launcher must already be
listening before starting the helper.

1. Helper sends `SHA256(ASCII("WOW-SESSION-CLIENT-1") || nonce)` (32 raw bytes).
   The launcher verifies it, then returns
   `SHA256(ASCII("WOW-SESSION-SERVER-1") || nonce)` (32 raw bytes). The helper
   verifies the distinct server proof before sending device data. The raw nonce
   is never sent; reflecting the client's proof cannot authenticate the server.
2. Helper sends a UTF-8 JSON device fingerprint as a four-byte big-endian length
   followed by the bytes (maximum 4096 bytes).
3. Launcher sends a fresh game token with the same framing (1–2048 printable ASCII
   bytes). Browser authentication may take up to 15 minutes.
4. Helper writes `WEB_TOKEN` as DPAPI-protected `REG_BINARY` and `ACCOUNT_TS` as a
   Unix timestamp string under
   `HKCU\Software\Blizzard Entertainment\Battle.net\Launch Options\WoW`.
   Stale `GAME_ACCOUNT` and `REGION` values are removed, allowing account choice.
5. It refuses to launch when `login.txt` exists beside the game, starts the child
   directly in its directory, sends the framed ASCII message `STARTED`, and closes
   the socket. Authenticated failures send only `ERROR:<stage>`, never credentials.
6. On normal child exit or a handoff error it removes its token/timestamp. Abrupt
   termination can leave DPAPI-protected registry state; no plaintext token is
   written to a file, log, process argument, or environment variable.

The host must reject other connections or incorrect nonces, bound all frames,
remove `login.txt` before launch, and pass the appropriate `-launcherlogin` game
argument. A named mutex prevents two helper launches from overwriting each other
in the same Wine session. There is no password-login fallback.

Error stages: 2 bootstrap/arguments; 3 concurrent launch; 4 Winsock setup;
5 connect; 6 nonce handshake; 7 device identity; 8 token frame; 9 game path or
`login.txt`; 10 DPAPI; 11 registry handoff; 12 process creation. Failures before
mutual authentication return an exit code without sending a status frame.

## Build and checks

```sh
tools/wow-session-bridge/test.sh
tools/wow-session-bridge/build.sh /path/to/x86_64-w64-mingw32-gcc /path/to/wow-session-bridge.exe
python3 tools/wow-session-bridge/test_integration.py --wine /path/to/wine64 --compiler /path/to/x86_64-w64-mingw32-gcc
```

Build with LLVM-MinGW 20261104 (Clang 23.1.5), x86_64 Windows UCRT. The x86_64 helper
uses the Wine emulator for CPUID and Windows APIs. Its fingerprint must match the
authentication platform used by the Android broker; ARM64 client compatibility
still needs on-device validation. PE timestamps are disabled for reproducibility.
The integration test creates a disposable Wine prefix and uses a synthetic token
with a dummy child to verify transport, DPAPI, launch refusal, and cleanup. It does
not contact Battle.net or use account credentials.

`device_id.c` and `device_id.h` are copied unchanged from
[nyrda/scuffle](https://github.com/nyrda/scuffle), commit
`5efccb44777bba7e28e70ef782564b713e7373b5`, under the MIT license in
`LICENSE.scuffle`. Its output calls are redirected at compile time to a bounded
in-memory buffer. The DPAPI entropy and registry handoff follow Scuffle's
`bridge/auth_bridge.c` at the same commit. The remaining bridge code is part of
WoW Forever and follows the repository's license.
