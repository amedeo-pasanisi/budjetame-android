# Building and releasing the Android app

## Environments & pipeline

Two backend environments, switched by `buildConfigField` in `app/build.gradle.kts`:

| Build type | Backend URL | Purpose |
|---|---|---|
| `debug` | `https://stage.budjetame.de/api/` | Development — real environment, throwaway registered Accounts |
| `release` | `https://budjetame.de/api/` | Production — what users see |

There is **no CI/CD pipeline** for the Android app. Every release is built
locally and published manually to Google Play Console. The web app's
automated dev→stage→prod pipeline (ADR-0023) does not apply here — the
shared backend runs that pipeline; the Android app is just another client
of it.

## Prerequisites

- Android SDK (set `sdk.dir` in `local.properties` or `ANDROID_HOME`).
- JDK 17 (bundled with Android Studio, or standalone).
- A **release keystore** (Play Upload Key) outside the repo. The Play
  Console registers the upload key's SHA-1 fingerprint; Google Play
  App Signing re-signs the AAB with its own key before distributing.

## Build commands

```bash
./gradlew test                  # JVM unit tests (MockWebServer seam) + pure unit tests
./gradlew assembleDebug         # unsigned debug APK (stage backend)
./gradlew installDebug          # debug APK on a connected device/emulator
./gradlew bundleRelease         # signed release AAB (production backend, Play upload)
./gradlew assembleRelease       # signed release APK (production backend, sideload)
```

- `bundleRelease` produces a `.aab` — the format Google Play requires.
- `assembleRelease` produces a `.apk` — for sideloading outside Play.
- Both `release` tasks sign with the upload keystore (if configured), or
  produce unsigned artifacts (which Play Console will reject / the device
  won't install).

### Signing

The upload keystore lives outside the repo. Set these Gradle properties
(e.g. in `~/.gradle/gradle.properties` — never commit a key or password):

```properties
RELEASE_KEYSTORE_PATH=/path/to/budjetame-upload.jks
RELEASE_KEYSTORE_PASSWORD=…
RELEASE_KEY_ALIAS=budjetame
RELEASE_KEY_PASSWORD=…
```

Without them, release builds come out unsigned (a developer machine with
no keystore can still run `bundleRelease` for testing, but the artifact
is not installable or uploadable).

### Google Maps provider

The Transaction form's map picker sits behind a provider seam
(ADR-0004). By default the app uses the **free osmdroid/OpenStreetMap**
picker (no key, tap-only, coordinates-only picks). To enable the Google
Maps picker (place search + POI taps + Place references), set these
Gradle properties (e.g. in `~/.gradle/gradle.properties`):

```properties
MAP_PROVIDER=google
GOOGLE_MAPS_API_KEY=AIza…
```

Run `scripts/google-maps-wizard.sh` to be walked through the Google
Cloud setup (enabling Maps SDK for Android + Places API, registering the
upload-key SHA-1 fingerprint on an Android-restricted key). Anything that
is not exactly `google` selects the free picker; a `google` build without
a key fails loudly at render time.

### The authorization trap: which fingerprint to register

Google Maps authorizes the app by **package name + the SHA-1 of the
certificate that signs the installed APK**. The same source can be signed
by one of three different certificates depending on how it reaches a
phone, and **each has its own fingerprint**. Registering the wrong one is
the #1 cause of "the map doesn't load" after everything else looks right.

| How the app reaches a phone | Which certificate signs it | Fingerprint needed on the key |
|---|---|---|
| Debug build (`./gradlew installDebug`, Android Studio run) | The **debug keystore** (auto-generated `~/.android/debug.keystore`) | Debug SHA-1 |
| Sideloaded **release** APK (`assembleRelease`) | The **upload key** from `RELEASE_KEYSTORE_PATH` | Upload-key SHA-1 |
| Installed from **Google Play** | The **Play App Signing key** (different again — Play re-signs every upload) | App-signing SHA-1 |

Because Play re-signs the AAB you upload, the certificate a Play-installed
user runs is the **Play App Signing key**, not the upload key you signed
locally. So Play distribution needs the app-signing fingerprint, and
sideloading the same source as an APK needs the upload-key fingerprint —
they are different. Register all three on the key to avoid surprises.

**Which SHA-1s to paste:** run `./gradlew signingReport` to print the
debug and release (upload-key) fingerprints. The Play App Signing
fingerprint comes from **Play Console → the app → Test and release →
Setup → App signing**. Copy them as `AB:CD:EF:…` with colons, matching the
format the Console expects.

> ⚠️ **The machine that signs the APK decides which fingerprint matters.**
> If you build the APK on a *different* machine than the one holding the
> debug keystore, its debug fingerprint is different — run
> `./gradlew signingReport` on the machine that **actually produces** the
> APK you install.

A `google` key that is missing the right fingerprint fails silently at
the map layer (a 20-second timeout in `GoogleMapPicker` shows an error), so
if the map won't render, check the fingerprint on the key against
`signingReport` before looking anywhere else.

## Versioning

SemVer (`X.Y.Z`), matching the web repo's release tags. Two values in
`app/build.gradle.kts` must be bumped together before every release:

```kotlin
defaultConfig {
    versionCode = 10       // monotonically increasing integer (Play requires it)
    versionName = "1.7.0"  // user-facing version string
}
```

- **`versionCode`**: incremented by 1 for every release (never decremented,
  never reused). Play Console uses this to decide whether an AAB is newer
  than the installed version.
- **`versionName`**: the `vX.Y.Z` tag string, without the `v` prefix.

## Release ritual

1. **Work on `main`** — commits land on `main` directly. No release
   branch: the Android app is a single-module project with no deploy
   pipeline, so there's nothing to gate between `main` and a release.

2. **Update `CHANGELOG.md`** — move the `[Unreleased]` section to a
   `[vX.Y.Z] — YYYY-MM-DD` heading, add the release link at the bottom
   (matching the existing format). See `docs/agents/changelog.md`.

3. **Bump the version** in `app/build.gradle.kts`:
   - `versionCode` incremented by 1
   - `versionName` set to the new version string (e.g. `"1.8.0"`)

4. **Commit** the changelog edit and the version bump on `main`:
   ```bash
   git commit -m "release: vX.Y.Z — changelog cut, versionName X.Y.Z"
   ```

5. **Tag the commit**:
   ```bash
   git tag -a vX.Y.Z -m "vX.Y.Z"
   git push origin vX.Y.Z
   ```

6. **Build the release AAB**:
   ```bash
   ./gradlew clean bundleRelease
   ```
   The signed AAB lands at `app/build/outputs/bundle/release/app-release.aab`.

7. **Verify the artifact** — check that the APK from the AAB (use
   `bundletool build-apks`) installs and talks to the production backend,
   and that signing is correct:
   ```bash
   jarsigner -verify -certs app/build/outputs/bundle/release/app-release.aab
   ```

8. **Upload to Google Play Console**:
   - Navigate to **Production > Create new release**.
   - Upload the `.aab`.
   - Fill release notes (paste the changelog section).
   - Roll out: start with a staged rollout (e.g. 10 %), monitor crashes
     and ANRs in Play Console's **Android vitals** for 24–48 hours, then
     roll out to 100 %.

9. **Create the GitHub Release** at the tag (optional, but good practice
   for record-keeping): `gh release create vX.Y.Z --title "vX.Y.Z" --notes "…"`

### Rollback

Google Play does not support instant rollback of an AAB — once a release
is rolled out, users who installed it keep it until they upgrade. To
stop an ongoing staged rollout: halt it in Play Console (existing users
keep the version, new installs get the previous live version). For a
critical fix: release a new patch version (X.Y.Z+1) with the fix.

### Caveat: sideload vs Play install

Google Play App Signing re-signs the AAB with its own key (the **app
signing key**), which is different from the **upload key** used to sign
the AAB locally. This means:

- A user who installed the Play version cannot install a sideloaded
  release APK (signed with the upload key) on top — the signatures differ.
  They must uninstall first.
- A sideloaded debug APK (unsigned, or signed with the debug keystore)
  cannot be installed over a release build either.

This is expected behaviour on Android and affects only development
machines that run both Play-installed and sideloaded builds.