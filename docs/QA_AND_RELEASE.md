# OpenVTA Logger QA And Release Runbook

## Local Prerequisites

- macOS or Linux development machine.
- JDK 17.
- Android SDK with build-tools and platform-tools.
- Android emulator for optional connected tests in the dedicated mobile QA
  thread.
- `gh` CLI for GitHub Actions and release operations.
- Release signing files under ignored `release-signing/` for local signed builds.

## Standard Local Verification

Run before pushing functional changes:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Optional connected emulator QA:

```bash
./gradlew connectedDebugAndroidTest
./scripts/emulator_verify.sh
```

Connected emulator and physical-device runs are not part of the default local
or CI cycle. Use them from the dedicated Android QA thread when validating
recording, mock GPS, permissions, foreground service behavior, or release
candidate acceptance.

Expected emulator smoke-test coverage:

- install debug APK
- launch `dev.openvta.logger/.MainActivity`
- grant required permissions
- inject mock GPS route
- verify live recording
- verify VTA file creation
- verify screen-off file growth
- verify ZIP creation
- capture screenshots under `/tmp`

## Release APK Local Build

Create ignored signing files:

```text
release-signing/openvta-logger-release.jks
release-signing/keystore.properties
```

`keystore.properties` format:

```properties
storeFile=openvta-logger-release.jks
storePassword=...
keyAlias=openvta-logger-release
keyPassword=...
```

Build:

```bash
./gradlew assembleRelease
```

Verify:

```bash
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
$ANDROID_HOME/build-tools/35.0.0/aapt dump badging app/build/outputs/apk/release/app-release.apk | head
```

Expected current release metadata:

```text
package: dev.openvta.logger
versionCode: 3
versionName: 0.0.3
minSdk: 29
targetSdk: 35
```

Current code metadata for the internal `0.0.4` candidate:

```text
package: dev.openvta.logger
versionCode: 4
versionName: 0.0.4
minSdk: 29
targetSdk: 35
```

## Release Signing And Secret Rotation

Signing material must never be committed.

GitHub release workflow expects these `release` environment secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Current public release line:

- alias: `openvta-logger-release`
- certificate SHA-256:
  `9b847b690875bc1fbb701f64e881ce1d718d04349a8aa044f6a7107d9d5a4325`

Important: because this app is distributed directly as APK, future `dev.openvta.logger` releases must be signed with the same key for Android update compatibility.

## GitHub Actions

### Android CI

Workflow:

```text
.github/workflows/android-ci.yml
```

Default checks on `main`:

- `build-and-unit-test`

Manual-only check:

- `connected-emulator-test`, available through `workflow_dispatch` with
  `run_connected_emulator=true`

Latest verified `v0.0.3` CI:

- run `27780826017`
- result: success
- build/unit/lint: success
- connected emulator: success

Latest default Android CI for the current `0.0.4` code line:

- run `28891705229`
- result: success
- build/unit/lint: success
- connected emulator: intentionally not part of default CI

### Release APK

Workflow:

```text
.github/workflows/release-apk.yml
```

Triggers:

- `workflow_dispatch`
- push tags matching `v*`

Latest verified `v0.0.3` release workflow:

- run `27781347850`
- result: success
- restored release signing material
- built signed release APK
- verified APK signature
- uploaded release artifact

Latest verified internal `0.0.4` release workflow:

- run `28749474216`
- result: success
- trigger: tag `v0.0.4`
- public GitHub Release page: not created yet
- APK artifact and SHA-256 evidence: keep outside Git

## Publishing A New Release

1. Update `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Update docs and user-facing release notes.
3. Run local verification.
4. Commit and push.
5. Wait for default Android CI success.
6. Run manual connected emulator or physical-device QA only when the release
   owner requests mobile acceptance evidence in the dedicated QA thread.
7. Tag release:

```bash
git tag -a vX.Y.Z -m "OpenVTA Logger X.Y.Z"
git push origin vX.Y.Z
```

8. Wait for Release APK workflow success.
9. Create GitHub Release and upload APK:

```bash
gh release create vX.Y.Z output/apk/OpenVTA-Logger-X.Y.Z-release.apk \
  --repo woo4826/openvta-logger-android \
  --title "OpenVTA Logger X.Y.Z" \
  --notes "Release notes here"
```

10. Verify public download:

```bash
curl -I -L https://github.com/woo4826/openvta-logger-android/releases/download/vX.Y.Z/OpenVTA-Logger-X.Y.Z-release.apk
```

## Manual Acceptance Checklist

Use the final release APK, not only debug:

- install APK on emulator or physical device
- launch app
- allow location and notification permissions
- start recording
- verify Live map receives location and path
- stop recording
- verify Sessions shows VTA file
- press Zip and verify ZIP size becomes nonzero
- press Upload without FTP host and verify clean error message, no crash
- verify Share VTA/Share ZIP opens Android sharesheet when files exist
- if testing OpenVTA Live, pair with a disposable six-digit registration code
  and disposable test user/device
- verify Live upload/command evidence through user/admin web only in the
  dedicated mobile QA thread
- check crash buffer:

```bash
adb logcat -b crash -d
```

## Local FTP Smoke Test

There is a helper:

```bash
./scripts/local_ftp_upload_verify.sh
```

Use only local/test FTP targets. Never point automated tests at production servers.

## Known CI Warning

GitHub currently emits Node.js 20 deprecation warnings for several actions while forcing Node.js 24. These warnings did not fail the latest verified workflows, but action versions should be reviewed periodically.
