# Contributing to ForgeKit

Thanks for helping improve ForgeKit. This guide covers how to propose changes and what a pull
request needs before it can be merged.

## Before you start

- For bugs, search [existing issues](https://github.com/itzmejanak/ForgeKit/issues) first, then
  open one with the bug report form.
- For new features or architectural changes, open an issue to discuss the approach before
  writing code. Major architectural changes need an ADR (`STRUCTURE.md` §12.3).
- Security problems must **not** be reported in public issues — see [`SECURITY.md`](SECURITY.md).

## Development setup

1. Install JDK 21 and the Android SDK (platform 35, build-tools 35, NDK `27.0.12077973`,
   CMake `3.22.1`).
2. Point Gradle at the SDK with `ANDROID_HOME` or `sdk.dir` in `local.properties`
   (never commit `local.properties`).
3. Run the checks CI runs:

   ```bash
   ./gradlew test
   ./gradlew :app:assembleDebug
   ```

4. To try changes on a device, install the debug APK on an arm64 phone:
   `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

## Where code goes

Read [`Docs/ARCHITECTURE.md`](Docs/ARCHITECTURE.md) and [`STRUCTURE.md`](STRUCTURE.md) first.
`STRUCTURE.md` defines which module owns each concern and the dependency rules between modules —
for example, UI code never starts processes and `ui/design` depends only on `core/common`.
[`Docs/RULES.md`](Docs/RULES.md) lists the rules every change must satisfy.

## Pull requests

- Keep each pull request focused on one change.
- Add or update tests for behavior you change. Tests use real processes, files and archives —
  do not add mocks to the acceptance path.
- Update the relevant documentation (`Docs/`, `Docs/developer/`, module `README.md`) in the
  same pull request.
- Add a line under **Unreleased** in [`CHANGELOG.md`](CHANGELOG.md) for user-visible changes.
- Follow the existing Kotlin style (`kotlin.code.style=official`) and match the surrounding code.
- Use clear commit messages; the history uses [Conventional Commits](https://www.conventionalcommits.org/)
  prefixes such as `feat:`, `fix:`, `refactor:`, `docs:`.
- New third-party components must be recorded in
  [`third-party/THIRD_PARTY_NOTICES.md`](third-party/THIRD_PARTY_NOTICES.md) with their license text.

CI must pass before review. A maintainer merges once the change is approved.

## Licensing of contributions

ForgeKit is licensed under the GNU General Public License v3.0. By submitting a contribution,
you agree that it is licensed under the same terms (see [`LICENSE`](LICENSE)).

## Code of Conduct

Everyone taking part in the project is expected to follow the
[Code of Conduct](CODE_OF_CONDUCT.md).
