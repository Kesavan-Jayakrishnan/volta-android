# Architecture

Volta is a multi-module app with one rule: **dependencies point inwards.** UI depends on the domain; the domain
depends on nothing Android; the data layer implements the domain's interfaces.

```
                 ┌──────────────┐
                 │     :app     │  Activity, navigation, Hilt entry point
                 └──────┬───────┘
          ┌─────────────┼──────────────────┐
          ▼             ▼                  ▼
 ┌─────────────────┐ ┌────────────┐ ┌────────────────────┐
 │:feature:onboard.│ │ :core:data │ │ :core:designsystem │
 │ screens + VMs   │ │ repos, DI  │ │ theme, components  │
 └────────┬────────┘ └─────┬──────┘ └────────────────────┘
          │                │ implements
          ▼                ▼
       ┌──────────────────────┐
       │     :core:domain     │  interfaces + use cases (pure Kotlin)
       └──────────┬───────────┘
                  ▼
 ┌────────────┐ ┌────────────┐ ┌──────────────┐ ┌─────────────┐
 │ :core:maps │ │:core:ismart│ │ :core:planner│ │ :core:model │   pure Kotlin
 └────────────┘ └────────────┘ └──────────────┘ └─────────────┘
```

## Modules

| Module | Kind | Responsibility |
|---|---|---|
| `:core:model` | Kotlin | Vehicle profile, status and tariff models. |
| `:core:planner` | Kotlin | Energy model and trip planner (charging stops, meal stops, re-planning). |
| `:core:ismart` | Kotlin | MG i-SMART client: login, vehicle list, status, commands. |
| `:core:maps` | Kotlin | Google Maps Platform transport, error mapping, key checker. |
| `:core:domain` | Kotlin | Repository interfaces (`AccountRepository`, `MapsKeyRepository`, `SetupRepository`) and use cases. |
| `:core:data` | Android | Implementations: Keystore-encrypted storage, DataStore, Hilt bindings. |
| `:core:designsystem` | Android | Theme, colours, type, icons and shared components (`WtButton`, `WtNote`, `StepBar`). |
| `:core:testing` | Kotlin | Fakes and `MainDispatcherRule`, shared by every module's unit tests. |
| `:feature:onboarding` | Android | Sign-in and Google key screens. |
| `:app` | Android | `MainActivity`, the first-run gate and navigation. |

Four of the modules have no Android dependency at all, so their tests run on the plain JVM in seconds.

## Patterns

**Unidirectional data flow.** Each screen has a `ViewModel` exposing one immutable `UiState` as a `StateFlow`, and
plain functions for events. Composables are split into a *route* (gets the ViewModel from Hilt) and a stateless
*screen* (state in, callbacks out), so screens preview and test without any ViewModel.

**Interfaces at the boundary.** ViewModels and use cases see only `core:domain` interfaces. `core:data` binds the
real implementations in Hilt; tests pass the fakes from `core:testing`.

**Use cases for rules that span repositories.** `CheckAndSaveMapsKeyUseCase` checks a key with Google and saves it
only if every API passed. `ObserveStartDestinationUseCase` decides whether the app opens on sign-in, the key step
or home.

**Errors as types.** The MG client's exceptions become a `SignInError` sealed type; Google's error bodies become a
`KeyProblem` enum. The UI turns each case into a specific message and a specific fix.

## The Google key, end to end

1. The user pastes a key. `MapsKeyViewModel` rejects anything that isn't shaped like a Google key, without a request.
2. `CheckAndSaveMapsKeyUseCase` calls `MapsKeyChecker.check`. `GoogleMapsKeyChecker` makes the cheapest real
   request to each API (a Places text search for IDs only, a two-point route for distance only).
3. `GoogleApi` sends the key with the app's package name and signing SHA-1, which Google checks for keys
   restricted to Android apps. Failures are parsed by `GoogleErrorParser`.
4. If both pass, `SecretMapsKeyRepository` encrypts and stores the key.
5. Every later Google call reads the key through `MapsKeyProvider` at call time, so a new key works immediately.

## Secrets

`KeystoreSecretStore` encrypts each value with AES-256-GCM using a non-exportable Android Keystore key and a fresh
IV per write. The preferences file is excluded from cloud backup and device transfer.
