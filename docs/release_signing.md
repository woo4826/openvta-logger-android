# Release Signing Strategy

## Recommendation

Do not commit the Android release keystore to git, even in a private repository. Keep regular CI and pull request validation unsigned/debug-only, and run release signing only from a maintainer-controlled workflow or a maintainer machine.

For open source collaboration, use this split:

- Pull requests: build debug APK, unit tests, lint, and emulator tests. No release secret access.
- Maintainer release: protected GitHub Actions environment named `release`, manual approval, and release signing secrets.
- Play Store distribution: use Google Play App Signing and store only the upload key in CI.
- Direct APK distribution: protect the release keystore more strictly because that key is the app update identity for users.

## GitHub Secrets

The `Release APK` workflow expects these secrets in the `release` environment or repository Actions secrets:

- `ANDROID_KEYSTORE_BASE64`: base64-encoded `.jks` file.
- `ANDROID_KEYSTORE_PASSWORD`: keystore password.
- `ANDROID_KEY_ALIAS`: key alias.
- `ANDROID_KEY_PASSWORD`: key password.

Create the base64 value locally:

```bash
base64 -i release-signing/vta-logger-release.jks | pbcopy
```

On Linux:

```bash
base64 -w 0 release-signing/vta-logger-release.jks
```

GitHub officially documents base64-encoding small binary blobs for Actions secrets. Environment secrets can also be protected with required reviewers, so the release job cannot access them until approval is granted.

## Workflow Policy

Use `.github/workflows/android-ci.yml` for normal collaboration and `.github/workflows/release-apk.yml` only for releases.

Safe defaults:

- Do not expose signing secrets to `pull_request` jobs.
- Do not use `pull_request_target` to build untrusted contributor code with secrets.
- Prefer `workflow_dispatch` or protected `v*` tags for signed artifacts.
- Add required reviewers to the `release` environment before inviting external collaborators.
- Rotate the upload key if it is ever exposed.

## Play Store Versus Direct APK

With Play App Signing, Google stores the app signing key and CI can use an upload key. If the upload key is lost or compromised, it can be reset in Play Console.

For direct APK sharing outside Play Store, users can only update to future APKs signed with the same release key. Losing or exposing that key is a serious release-management problem, so keep an offline backup and restrict CI access.

## Local Release Build

Local signing still uses ignored files under `release-signing/`:

```properties
storeFile=vta-logger-release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Then build:

```bash
./gradlew assembleRelease
```

The generated APK should be verified before distribution:

```bash
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --verbose app/build/outputs/apk/release/app-release.apk
```
