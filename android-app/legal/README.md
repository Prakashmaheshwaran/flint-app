# Offline legal assets

`:app:generateOfflineLegal` runs before every app build. It copies
`docs/privacy-policy.md` byte for byte and generates notices from the **resolved
releaseRuntimeClasspath plus coreLibraryDesugaring artifacts**, including transitive
libraries. Internal project modules and debug/test-only dependencies are excluded.
It resolves POMs using Gradle's configured repositories, preserves embedded notices
(including nested AAR JARs), and includes full license texts and reviewed upstream
notices. There is no network or WebView code in the legal UI and no new runtime SDK.

Outputs are in `app/build/generated/offlineLegal/assets/legal/`:

- `privacy-policy.md`: the canonical policy, without rewriting or translation.
- `open-source-licenses.txt`: readable dependency/font inventory and full notices.
- `dependency-list.json`: coordinates, scopes, license declarations, notice references,
  POM/artifact/font hashes and policy hash; no machine paths or timestamps.

Run `./gradlew :app:generateOfflineLegal :app:testOfflineLegalGenerator` (on this
shared host, use the mandatory `flint-heavy android` semaphore). Gradle may fetch
dependencies/metadata at **build time**; the generated app needs no network access.
Generation deliberately runs each time so policy-only edits and resolution changes
cannot leave stale bundled text. This task opts out of Gradle's configuration cache
because it resolves POM metadata at execution time; other tasks retain that setting.
`./gradlew test`, as used by Android CI, also runs the Python generator tests.

Unknown license declarations, absent POM licenses, unreviewed source-notice versions
and new/unlicensed font files fail the build. Review new metadata and bundle the
actual full license/notice before adding support; never infer a license from a
group name. `supplemental.json` lists reviewed snapshots for binaries that omit
upstream notices. Font licenses come from the existing `design/fonts/*-OFL.txt`
copies, and font modification descriptions match `design/fonts/README.md`.

## Vendored source provenance

- Apache 2.0: verbatim `META-INF/androidx/annotation/annotation/LICENSE.txt` in
  `androidx.annotation:annotation-jvm:1.9.1` (published Maven artifact).
- Kotlin copyright header: upstream [`license/COPYRIGHT.txt` at v2.2.10](https://github.com/JetBrains/kotlin/blob/v2.2.10/license/COPYRIGHT.txt).
- Coroutines notice: upstream [`license/NOTICE.txt` at 1.10.2](https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.2/license/NOTICE.txt).
- Desugar: upstream [2.1.5 preparation commit `73170c3`](https://github.com/google/desugar_jdk_libs/commit/73170c345e6a762fc6a1f0301bb15218850023ef),
  including `LICENSE` (GPLv2 + Classpath Exception), `ADDITIONAL_LICENSE_INFO`,
  `ASSEMBLY_EXCEPTION` and source notices corresponding to release JAR classes.
  Both OpenJDK and Android source variants are preserved conservatively, including
  their BSD/public-domain/Apache notices. To reproduce the source notice snapshot,
  pass that commit's GitHub tarball and the released `desugar_jdk_libs:2.1.5` JAR to
  `python3 legal/import_desugar_notices.py <archive> <jar> <output-directory>`.
  This import helper never runs during an app build and performs no downloads.

The resolved list describes release build inputs, not which classes survive R8.
Document bodies retain upstream language; all new navigation/status/error UI labels
are Android string resources ready for localization. URLs in legal text remain
selectable text and never open a browser automatically.
