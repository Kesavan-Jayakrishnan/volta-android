# Building Volta

## Requirements

- JDK 21 (Android Studio's bundled JDK works)
- Android SDK with platform 37
- Android Studio, or just the command line

## Debug build

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

No API keys are needed at build time. Copy `local.properties.example` to `local.properties` only if Android Studio
hasn't created one for you (it holds `sdk.dir`).

## Tests

```bash
./gradlew test                       # every module
./gradlew :core:maps:test            # one module
```

## Your Google key and the debug SHA-1

If you restrict your Google key to Android apps (recommended), add the debug certificate of your machine:

```bash
./gradlew :app:signingReport         # look for "Variant: debug", "SHA1"
```

## Signed release build

Release signing is read from `local.properties` or environment variables, never from the repository:

```properties
VOLTA_STORE_FILE=/absolute/path/to/release.jks
VOLTA_STORE_PASSWORD=...
VOLTA_KEY_ALIAS=...
VOLTA_KEY_PASSWORD=...
```

```bash
./gradlew :app:assembleRelease
```

Without these the release APK is built unsigned.

## Publishing a release

Pushing a tag like `v0.1.0` runs [the release workflow](../.github/workflows/release.yml). The tag has to match
`versionName`. It runs the tests, builds and signs the APK, then publishes `volta.apk` with its SHA-256 and the signing
certificate's SHA-1. Anyone can pull a key out of a public APK, so the `MAPS_API_KEY` built into it must be restricted
to the Maps SDK and this app's package and certificate. Google doesn't charge for Maps SDK use. Never build in a key that
can call Routes or Places.

It needs five repository secrets: `VOLTA_KEYSTORE_BASE64` (the keystore, base64-encoded), `VOLTA_STORE_PASSWORD`,
`VOLTA_KEY_ALIAS`, `VOLTA_KEY_PASSWORD` and `MAPS_API_KEY`.

Keep a backup of the keystore outside this repository. Android only installs an update signed with the same key, so
losing it means everyone has to uninstall and start again.
