# Contributing

Thanks for helping. Bug reports, "works on my car" reports and pull requests are all welcome.

## Before you start

- For anything bigger than a small fix, open an issue first so we can agree on the approach.
- One change per pull request. Small PRs get reviewed quickly.
- UI changes: include before and after screenshots.

## Build and test

```bash
./gradlew :app:assembleDebug
./gradlew test
./gradlew spotlessApply detekt
```

All tests must pass. New logic comes with unit tests; ViewModels are tested against the fakes in `core/testing`.

## Code

- Follow the layering in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): UI depends on `core:domain` interfaces,
  never on `core:data` or network clients directly.
- ktlint formats the code (`spotlessApply`) and detekt checks it. Keep functions small and names plain.
- Comments say *why*, not *what*.
- Commit messages: imperative subject under 72 characters ("Add key check for Routes API"), body when the
  reason isn't obvious.

## What's out of scope

- Anything that sends a command to a car (lock, unlock, climate) without the owner confirming it on screen.
- Analytics, tracking or any server that receives user data.
- Calls to MG's service beyond what the official app makes.

## Your contribution and the licence

Volta is licensed under the [Apache License 2.0](LICENSE). By opening a pull request you confirm the contribution is
your own work, or that you have the right to submit it, and you license it under the same terms.

## Security

Please don't open public issues for security problems. See [SECURITY.md](SECURITY.md).
