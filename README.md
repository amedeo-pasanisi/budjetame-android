# Budjetame (Android)

The native Android client of the Budjetame backend — a second frontend of the
same product, with the same data and the same behavior as the web app
(`budjetame.de`). The domain glossary lives in [`CONTEXT.md`](CONTEXT.md)
(ported from the web repo — keep the two in sync); architectural decisions in
[`docs/adr/`](docs/adr/).

## Architecture

- **Client of the shared backend** (ADR-0001): online-only, no local cache —
  all domain logic lives server-side. Debug builds talk to
  `https://stage.budjetame.de/api/`, release builds to
  `https://budjetame.de/api/` (via `BuildConfig`).
- **Screens keep loaded data and revalidate in the background after writes**
  (ADR-0002): a global data version bumped by the transport after every
  successful write; each tab's ViewModel is scoped to its navigation
  back-stack entry.
- **Single module, single activity**, Jetpack Compose + Material 3, MVVM,
  no DI framework (manual composition root `AppContainer`).
- The session JWT is stored encrypted at rest with a Keystore-backed AES-GCM
  key (`TokenStore`).

## Stack (latest stable, verified 2026-09-01)

| Component        | Version       |
|------------------|---------------|
| Kotlin           | 2.4.10        |
| Android Gradle Plugin | 9.3.2 (built-in Kotlin support — no `kotlin-android` plugin) |
| Gradle           | 9.7.1         |
| compileSdk / targetSdk | 37 (Android 17) |
| minSdk           | 26            |
| JVM target       | 17            |
| Compose BOM      | 2026.08.00    |
| Navigation Compose | 2.10.0      |
| Retrofit / OkHttp | 3.0.0 / 5.5.0 |
| kotlinx.serialization | 1.11.0    |
| Credential Manager / googleid | 1.6.0 / 1.2.0 |
| Google Maps / Places SDKs | 20.0.0 / 5.3.0 (optional) |
| osmdroid | 6.1.20 |

## Build

```bash
# Create local.properties with your SDK path (or set ANDROID_HOME):
#   sdk.dir=/path/to/android-sdk

./gradlew test                  # unit tests (JVM, MockWebServer seam)
./gradlew assembleDebug         # debug APK (→ stage.budjetame.de)
./gradlew installDebug          # install on a connected device/emulator
```

Notes:

- The Transaction form's map picker sits behind a provider seam (ADR-0004
  parity): **free by default** (osmdroid/OpenStreetMap — no key, tap-only,
  coordinates-only picks), Google Maps behind a build-time switch. To
  enable the Google picker — a billing account and API key are required,
  never commit a key — set these Gradle properties (e.g. in
  `~/.gradle/gradle.properties` for a private key):

  ```properties
  MAP_PROVIDER=google
  GOOGLE_MAPS_API_KEY=AIza…
  ```

  Anything that is not exactly `google` selects the free picker; a
  `google` build without a key fails loudly at render time instead of
  showing a broken map. Google picks (place search, POI taps) carry a
  Place reference; the free picker, GPS, and imports attach coordinates
  alone (ADR-0005).

- AGP 9 has **built-in Kotlin**: `org.jetbrains.kotlin.android` is not applied.
  Kotlin compiler options go in the `kotlin { compilerOptions { } }` block.
- The Compose compiler comes from `org.jetbrains.kotlin.plugin.compose`
  (versioned with Kotlin, 2.4.10); serialization from
  `org.jetbrains.kotlin.plugin.serialization`.
- The Retrofit kotlinx.serialization converter is hand-rolled in
  `Transport.kt` (the published converter artifact doesn't resolve under
  Kotlin 2.4) — don't "fix" it back to the artifact.
- **Building on an ARM64 Linux box**: `aapt2` ships x86-64 only. Install
  `qemu-user-static` plus the amd64 runtime libraries (`libc6:amd64`,
  `libstdc++6:amd64`, `libgcc-s1:amd64`, `zlib1g:amd64` — on an
  ubuntu-ports system add `archive.ubuntu.com` as an amd64-only source
  first). Everything else runs natively.

## Testing

The single seam is the HTTP API: behavior tests drive repositories and
ViewModels through real Retrofit/OkHttp against a `MockWebServer` whose
dispatcher routes by path. Pure logic (money/date formatting, error-detail
parsing, the data-version rule) gets direct JVM unit tests. Compose UI tests
cover only the fiddly flows (form validation, merge confirmation).

## Project layout

```
app/src/main/java/com/budjetame/android/
├── MainActivity.kt          # Single activity, edge-to-edge, Compose UI
├── AppContainer.kt          # Manual composition root (session, transport, repos)
├── BudjetameApp.kt          # Auth state machine + app shell gate
├── data/
│   ├── api/                 # Transport (Retrofit/OkHttp, data-version bump), API services + DTOs
│   ├── TokenStore.kt        # Keystore AES-GCM encrypted session JWT
│   ├── Session.kt           # In-memory token cache for the interceptor
│   ├── auth/                # AuthGateway + API-backed repository
│   ├── wallet/              # WalletGateway + API-backed repository
│   ├── category/            # CategoryGateway + API-backed repository (incl. merge conflict)
│   └── transaction/         # TransactionGateway + API-backed repository (ledger + write path)
├── ui/
│   ├── theme/               # Material 3, indigo/slate palette mirroring the web app
│   ├── login/               # Login/registration/Google/forgot + ViewModel
│   ├── wallets/             # Wallets tab: sections, create/rename/freeze, ViewModel
│   ├── categories/          # Categories tab: sections, search, CRUD, merge-on-rename, ViewModel
│   ├── dashboard/           # Dashboard tab: net worth, month totals + navigation, category pie (ticket #17)
│   ├── transactions/        # Transactions tab: ledger, paging, filters, search, and the create/edit/delete forms (#19, #20)
│   ├── common/              # Shared message bodies (loading, empty, load-error + retry)
│   ├── shell/               # AppShell: header, 5-tab NavigationBar, settings/deletion
│   └── screens/             # Remaining tab placeholder (Recurring)
└── util/                    # Money + date formatting (ports of the web app's format.ts)
```

Work is tracked as GitHub issues (spec #13, tickets #14–#29); see
`AGENTS.md` and `docs/agents/` for the workflow.
