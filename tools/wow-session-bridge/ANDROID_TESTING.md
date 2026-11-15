# Experimental Android browser session login

This branch implements a candidate alternative to direct password login for
[issue #57](https://github.com/jaredgei/wow-forever-android/issues/57).
It does not establish the server-side reason for BLZ51914400 or claim that the
ARM64 game accepts the token. The browser mode is off by default.

## Implemented path

1. Start the x86_64 helper in the same Wine prefix as the ARM64 game. It computes
   Scuffle's Windows device description from the actual emulated x64 context.
2. Authenticate the Android/helper loopback connection with distinct SHA256
   client/server proofs derived from a random 256-bit capability. The capability
   is held in app-private storage and deleted after the helper reads it.
3. Connect to the selected region's BGS service over hostname-verified TLS,
   using the installed game build and the helper's `Wn64` context.
4. Display the server's HTTPS Battle.net browser challenge. Read only the final
   main-frame `http://localhost:0/?ST=...` navigation, not form contents.
5. Reconnect, request a challenge, verify the one-use browser ticket and wait for
   successful authentication before issuing a separate game credential.
6. Pass that credential over the authenticated loopback connection. The helper
   protects it using Wine's DPAPI, sets `WEB_TOKEN` and `ACCOUNT_TS`, and launches
   the original game with `-launcherlogin -uid wowf -d3d11`.
7. Keep the helper alive for the child game's lifetime and remove its registry
   credential when the child exits normally. A new launch always requests a new
   token. There is no password fallback, token in argv, or plaintext token file.

The x64 broker to ARM64 game transition is an explicit compatibility question.
This implementation does not invent a `WinA` fingerprint or treat an OAuth
access token as a WoW game credential. It uses no desktop companion and does not
read the Battle.net desktop app's stored credentials.

## Device acceptance checks still required

- Use a test APK with this branch. Locally signed builds cannot update an
  official APK signed by another key. Preserve game files and avoid resetting a
  working installation just to compare identities.
- Leave browser login off and confirm the existing launch path is unchanged.
- Turn it on, press Play, and complete Battle.net's own page. Confirm the game
  reaches character selection and its Connection.log reports `usedToken=true`.
- Fully exit and launch again. Confirm both launches succeed without creating
  `_forever_/login.txt`. A first successful login alone is not sufficient.
- Test cancellation during page load, authenticator entry, and the final handoff.
  No game should continue launching after cancellation.
- Test a failed challenge, lost network, denied login and timeout. Each must
  stop cleanly with no automatic password attempt.
- Test Fold folded/unfolded layouts and switching to the authenticator app.
- Report device/Android/app/client versions, selected region, the visible stage,
  and redacted `logon`, `SRP`, and `login error` lines from Connection.log.
  Do not post browser URLs, tickets, tokens, cookies, account IDs or passwords.

## Checks that can run without a Battle.net account

```sh
./gradlew :app:testModernDebugUnitTest :app:assembleModernDebug
tools/wow-session-bridge/test.sh
tools/wow-session-bridge/build.sh /path/to/x86_64-w64-mingw32-gcc \
  app/src/main/assets/wow-session-bridge.exe
```

The JVM tests exercise actual protocol framing, notification ordering, rejected
authentication, URL parsing, callback boundaries and authenticated loopback
handoff. The C tests exercise framing, configuration and Windows quoting.
The synthetic Wine harness described in the bridge README checks the DPAPI and
process boundary with dummy credentials. None of these substitutes for an
authenticated login on the affected Android device.
