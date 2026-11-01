# Privacy

Volta is an app on your phone and nothing else. The project runs no servers, so there is nowhere for your data to
go except the two services the app works with.

## What Volta stores

All of it on your phone, in the app's private storage:

| Data | How it's stored |
|---|---|
| MG i-SMART mobile number and password | Encrypted, AES-256-GCM with an Android Keystore key |
| MG session token | Encrypted, same way |
| Your Google Maps Platform key | Encrypted, same way |
| Settings (such as whether you finished first-run setup) | Plain app preferences |

The encryption key lives in the Android Keystore and can't be exported. Volta turns off cloud backup and device
transfer for its data, so none of this is copied to Google Drive or a new phone.

## Who Volta talks to

| Service | What's sent | Why |
|---|---|---|
| MG i-SMART (MG Motor India's servers) | Your mobile number and password at sign-in, then your session token | To read your car's status, as the official MG app does |
| Google Maps Platform | Your API key, your app's package name and signing certificate, and the places a trip needs | Routes, charging stops and food stops |

Nothing else. No analytics, no advertising, no crash reporting, no Volta server.

Volta only *reads* from MG's service and keeps the result on your phone. It doesn't copy your car's data anywhere.

## Deleting your data

- **Sign out** removes your MG login and session. Your Google key stays until you remove it on the Google Maps key
  screen.
- **Uninstall** removes everything Volta stored. There's no copy anywhere else, so if you install Volta again you
  start fresh: sign in to MG and add your Google key again.

Your car data held by MG, and your Google Cloud project, are managed in MG's app and Google Cloud Console; Volta
can't change them.

## Questions

Open an issue on GitHub. For anything security-related, see [SECURITY.md](../SECURITY.md).
