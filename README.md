# Volta

An Android app for MG electric cars in India: your car's battery, range and charging on your phone, and trip plans
with charging and food stops worked out for the road ahead.

Volta talks to MG's i-SMART service with your own owner account, and to Google Maps Platform with your own API key.
There is no Volta server and no analytics: your login and key stay on your phone.

> Volta is an independent project. It is not made by, endorsed by or affiliated with MG Motor India or SAIC.
> "MG" and "i-SMART" are their trademarks, used here only to say what the app works with.

## Status

Early. This repository is being built up in stages:

| Stage | What | State |
|---|---|---|
| 1 | Project structure, MG sign-in, your own Google key (checked before it's saved) | Done |
| 2 | Dashboard: battery, range, charging, doors and tyres | Next |
| 3 | Trip planner: route, charging stops, meal stops | Planned |

Tested on the MG Windsor EV. Other MG EVs on i-SMART should sign in; their battery figures will follow.

## What you need

- An MG EV with an i-SMART **owner** account (India). Shared accounts don't receive battery data.
- An Android phone, Android 10 or newer.
- A Google Maps Platform API key of your own, for trip planning. [How to create one and cap its cost](docs/GOOGLE-API-KEY.md).

## First run

1. **Sign in** with your i-SMART mobile number and password.
   Signing in here signs the official MG app out (MG allows one session), and every wrong password counts against
   MG's daily limit, so Volta never retries a rejected password.
2. **Add your Google key.** Paste it and tap *Check key*. Volta makes one small request to each API it uses and
   tells you exactly what Google said if something is off (API not enabled, key restricted to another app, no
   billing). The key is saved only when every check passes. You can skip this and add it later.

## Your data

- The MG password, MG session and Google key are stored on the phone only, encrypted with AES-256-GCM under an
  Android Keystore key, and excluded from backups.
- They are sent only to MG's servers and to Google's APIs. Nothing goes anywhere else.
- Sign out and uninstall removes everything.

## Build from source

```bash
git clone https://github.com/irshadm/volta-android.git
cd volta-android
./gradlew :app:assembleDebug   # app/build/outputs/apk/debug/app-debug.apk
./gradlew test                 # unit tests for every module
```

Needs JDK 21 and the Android SDK (Android Studio sets both up). No keys are needed to build: the Google key is
entered in the app. See [docs/BUILD.md](docs/BUILD.md).

## How it's built

Kotlin, Jetpack Compose, Material 3, Hilt, Coroutines and Flow, OkHttp, kotlinx.serialization, DataStore.
A multi-module, layered codebase: pure-Kotlin domain and API modules, Android data and UI modules on top.
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) walks through it.

## Licence

[PolyForm Noncommercial 1.0.0](LICENSE.md). In short:

- **Free for personal and other noncommercial use**: use it, change it, build on it, share it.
- **Commercial use needs permission.** That includes selling the app or a modified version, or using the code in a
  paid app or service. Open an issue or get in touch to ask.

Because commercial use is restricted, this is *source-available* software rather than open source in the
OSI sense. Third-party code and icons keep their own licences; see [NOTICE.md](NOTICE.md).

Contributions are welcome under the terms in [CONTRIBUTING.md](CONTRIBUTING.md).
