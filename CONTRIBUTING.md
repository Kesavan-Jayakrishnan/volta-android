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
```

All tests must pass. New logic comes with unit tests; ViewModels are tested against the fakes in `core/testing`.

## Code

- Follow the layering in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): UI depends on `core:domain` interfaces,
  never on `core:data` or network clients directly.
- Kotlin official code style. Keep functions small and names plain.
- Comments say *why*, not *what*.
- Commit messages: imperative subject under 72 characters ("Add key check for Routes API"), body when the
  reason isn't obvious.

## What's out of scope

- Anything that sends a command to a car (lock, unlock, climate) without the owner confirming it on screen.
- Analytics, tracking or any server that receives user data.
- Calls to MG's service beyond what the official app makes.

## Your contribution and the licence

Volta is licensed under PolyForm Noncommercial 1.0.0. By opening a pull request you agree that:

1. the contribution is your own work, or you have the right to submit it; and
2. you license it to the project under the same terms as the project, **and** grant the maintainer the right to
   also license it under other terms, including commercial ones.

Point 2 keeps the option of offering commercial licences, which the noncommercial licence alone would block for
code the maintainer didn't write.

## Security

Please don't open public issues for security problems. See [SECURITY.md](SECURITY.md).
