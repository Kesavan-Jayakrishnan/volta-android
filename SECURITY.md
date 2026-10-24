# Security

Volta stores an MG account password and a Google API key on the phone, and can read data about a car. Please
report anything that could expose those, or act on someone's car, privately.

## How to report

Use GitHub's **private vulnerability reporting**: the *Security* tab of this repository → *Report a vulnerability*.
Please don't open a public issue.

Include what you found, how to reproduce it, and which version you tested. Expect a first reply within a week.

## In scope

- How credentials and the Google key are stored, or could leak (logs, backups, crash reports, other apps).
- Requests that send credentials or keys anywhere other than MG's servers and Google's APIs.
- Anything that could send a command to a car without the owner's confirmation.
