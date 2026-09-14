# Releasing ForgeKit

Production releases are built, signed and published by GitHub Actions
(`.github/workflows/release.yml`) when a version tag is pushed. Release signing material never
lives in the repository.

## One-time repository setup

### 1. Create the release signing key

Run this on your own machine (it refuses to write inside the repository):

```bash
bash tools/release/create-signing-key.sh          # creates ~/.forgekit-signing/forgekit-release.jks
```

Back up `forgekit-release.jks` and its password somewhere safe and offline. If the key is lost,
installed apps can never be updated in place again; if it leaks, anyone can publish APKs that
Android accepts as updates.

### 2. Create the `production` environment and its secrets

GitHub → **Settings → Environments → New environment** → name it `production`.

- **Deployment protection rules**: enable **Required reviewers** and add yourself, so every
  release waits for approval.
- **Deployment branches and tags**: choose **Selected branches and tags** and add the tag rule
  `v*`.
- **Environment secrets**:

| Secret | Value |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | content of `~/.forgekit-signing/forgekit-release.jks.base64` (delete that file afterwards) |
| `RELEASE_KEYSTORE_PASSWORD` | the keystore password |
| `RELEASE_KEY_ALIAS` | `forgekit` |
| `RELEASE_KEY_PASSWORD` | the same password (PKCS12 keystores use one password) |

### 3. Protect `main`

**Settings → Rules → Rulesets → New branch ruleset** for `main`:

- Require a pull request before merging (at least 1 approval once there are other maintainers).
- Require status checks to pass: **Test and build** (CI) and **Analyze Kotlin and Java** (CodeQL).
- Block force pushes and deletions.

Add a **tag ruleset** for `v*` that restricts creation, update and deletion to maintainers.

### 4. Security features

**Settings → Advanced Security** (or **Code security**):

- Enable **Private vulnerability reporting** (used by `SECURITY.md`).
- Enable **Dependabot alerts** and **Dependabot security updates** (version updates are
  configured in `.github/dependabot.yml`).
- Enable **Secret scanning** and **Push protection**.
- CodeQL runs from `.github/workflows/codeql.yml`; leave default setup disabled so both do not run.

**Settings → Actions → General**: set **Workflow permissions** to *Read repository contents*
(workflows request write access only where they need it).

## Publishing a release

1. Make sure `main` is green.
2. In `CHANGELOG.md`, move the **Unreleased** entries into a new section
   `## [X.Y.Z] - YYYY-MM-DD` and update the links at the bottom. The workflow publishes exactly
   this section as the release notes and fails if it is missing.
3. Verify the bundled third-party runtime and the release build locally:

   ```bash
   (cd third-party/termux-bootstrap && sha256sum -c SHA256SUMS)
   ./gradlew test :app:assembleRelease \
     :tools:forge-builder:distZip :tools:forge-validator:distZip
   ```

4. Commit, then tag and push:

   ```bash
   git tag -a vX.Y.Z -m "ForgeKit X.Y.Z"
   git push origin vX.Y.Z
   ```

   Supported pre-release tags are `vX.Y.Z-alpha.N`, `vX.Y.Z-beta.N` and `vX.Y.Z-rc.N`, where
   `N` is from 1 to 29. They are published as GitHub pre-releases.
5. Approve the **production** deployment in the Actions run.

The workflow then:

- runs the full test suite;
- builds `ForgeKit-X.Y.Z.apk` signed with the release key and derives both Android version fields
  from the tag;
- verifies the APK signature with `apksigner`;
- packages `forge-builder-X.Y.Z.zip`, `forge-validator-X.Y.Z.zip`, the project license and all
  third-party notices;
- writes `SHA256SUMS` and a signed build provenance attestation;
- creates the GitHub release with all files attached.

Android version codes remain monotonic across pre-releases and the stable release. For
`base = (X*10000 + Y*100 + Z)*100`, stable uses `base+99`, alpha uses `base+N`, beta uses
`base+30+N`, and release candidates use `base+60+N`. Minor and patch must be at most 99; the
derived code must not exceed Android's `2100000000` limit.

## Verifying a release

```bash
sha256sum -c SHA256SUMS
gh attestation verify ForgeKit-X.Y.Z.apk --repo itzmejanak/ForgeKit
apksigner verify --print-certs ForgeKit-X.Y.Z.apk   # compare with the published certificate fingerprint
```

## Building a signed release locally

Only needed to test signing; normal releases come from CI.

```bash
export FORGEKIT_KEYSTORE_FILE=~/.forgekit-signing/forgekit-release.jks
export FORGEKIT_KEY_ALIAS=forgekit
read -rs FORGEKIT_KEYSTORE_PASSWORD && export FORGEKIT_KEYSTORE_PASSWORD FORGEKIT_KEY_PASSWORD="$FORGEKIT_KEYSTORE_PASSWORD"
./gradlew :app:assembleRelease -Pforgekit.versionName=0.1.0 -Pforgekit.versionCode=1000099
```

Without any signing variables `assembleRelease` produces an unsigned APK. A partial signing
configuration always fails. Set `FORGEKIT_REQUIRE_SIGNING=true` to require all four variables even
when none are set.
