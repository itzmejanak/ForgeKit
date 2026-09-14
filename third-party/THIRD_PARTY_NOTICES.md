# Third-party notices

ForgeKit is licensed under the GNU General Public License v3.0 (see [`LICENSE`](../LICENSE)).
Distributed APK and command-line artifacts also contain the components below. Each component keeps
its own license. License texts are stored in [`licenses/`](licenses/) and are packaged into release
artifacts.

| Component | Version or pinned revision | License | Source | Distributed in |
|---|---|---|---|---|
| Termux arm64 bootstrap | `bootstrap-2025.03.28-r1+apt-android-7` | Per package | [provenance and inventory](termux-bootstrap/) | APK |
| IBM Plex Mono (Regular, Medium, SemiBold) | font version 2.3; source revision `0b58fb3` | SIL OFL-1.1 | [Google Fonts](https://github.com/google/fonts/tree/0b58fb370093f9a9f4ff785d94405710b79de67c/ofl/ibmplexmono) | APK |
| Chakra Petch (Regular, SemiBold, Bold) | font version 1.000; source revision `a4c8c2a` | SIL OFL-1.1 | [Google Fonts](https://github.com/google/fonts/tree/a4c8c2a0f77efa06765d596d64d077af1d7f0dae/ofl/chakrapetch) | APK |
| Bouncy Castle provider (`bcprov-jdk18on`) | 1.78.1 (`r1rv78v1`) | MIT-style Bouncy Castle license | [bc-java](https://github.com/bcgit/bc-java/tree/r1rv78v1) | APK, CLI tools |
| sqlite-jdbc (includes SQLite) | 3.46.1.3 | Apache-2.0; SQLite public domain | [xerial/sqlite-jdbc](https://github.com/xerial/sqlite-jdbc/tree/3.46.1.3) | APK |
| Kotlin standard library | 2.0.21 | Apache-2.0 | [Kotlin](https://github.com/JetBrains/kotlin) | APK, CLI tools |
| JetBrains annotations | 23.0.0 | Apache-2.0 | [java-annotations](https://github.com/JetBrains/java-annotations) | APK, CLI tools |
| kotlinx.coroutines | 1.9.0 | Apache-2.0 | [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | APK |
| kotlinx.serialization JSON | 1.7.3 | Apache-2.0 | [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) | APK, CLI tools |
| AndroidX and Jetpack Compose | versions resolved from `gradle/libs.versions.toml` | Apache-2.0 | [AndroidX](https://android.googlesource.com/platform/frameworks/support/) | APK |

JUnit, kotlin-test and AndroidX Test are test-only dependencies and are not distributed in release
artifacts.

## License texts

| Directory | Contents |
|---|---|
| [`licenses/termux-bootstrap/`](licenses/termux-bootstrap/) | GPL-3.0 text; the bootstrap itself contains all package license texts under `share/LICENSES/` |
| [`licenses/ibm-plex-mono/`](licenses/ibm-plex-mono/) | SIL Open Font License 1.1 and IBM Plex reserved-font-name notice |
| [`licenses/chakra-petch/`](licenses/chakra-petch/) | SIL Open Font License 1.1 and Chakra Petch copyright notice |
| [`licenses/bouncycastle/`](licenses/bouncycastle/) | License shipped by Bouncy Castle 1.78.1 |
| [`licenses/apache-2.0/`](licenses/apache-2.0/) | Apache License 2.0 used by Kotlin, kotlinx, AndroidX, sqlite-jdbc and JetBrains annotations |

## Termux bootstrap

The exact upstream asset, checksum, package versions, licenses and source/build recipes are split
into maintainable records:

- [`termux-bootstrap/PROVENANCE.md`](termux-bootstrap/PROVENANCE.md)
- [`termux-bootstrap/PACKAGES.md`](termux-bootstrap/PACKAGES.md)
- [`termux-bootstrap/SHA256SUMS`](termux-bootstrap/SHA256SUMS)

ForgeKit relocates the extracted files on the device; it does not modify the archive distributed in
the APK. ForgeKit is an independent project and is not affiliated with or endorsed by Termux.
