# Android metadata compatibility finding

During the managed-policy build, enabling core-library desugaring exposed extensive D8
metadata rewriting warnings in the existing dependency graph. The assembly still succeeded;
the warnings were not reported as a compilation failure.

## Cause and bounded change

The repository used AGP 8.7.3 (actual R8 8.7.18), Kotlin 2.1.21, and coroutines 1.11.0.
The cached coroutine bytecode carries Kotlin metadata version 2.2. Coroutines 1.11.0's
[official release](https://github.com/Kotlin/kotlinx.coroutines/releases/tag/1.11.0)
records its move to Kotlin 2.2.20, while the [Android compatibility table](https://developer.android.com/build/kotlin-support)
requires R8 8.10.21 for Kotlin 2.2.

Runtime dependency inspection showed that Kotlin stdlib 2.2.20 was selected through
coroutines Android/core/play-services 1.11.0. Other requests were 2.1.21 or older.
The shared version-catalog pin was changed to coroutines **1.10.2**, whose
[tagged build](https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.2/gradle.properties)
uses Kotlin 2.1.0 and whose [published dependency](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.10.2/kotlinx-coroutines-core-jvm-1.10.2.pom)
requests stdlib 2.1.0. No forced stdlib version or warning suppression was added.

This pin gives up fixes introduced in coroutines 1.11.0, including SharedFlow cancellation
and an R8 optimization issue involving `stateIn`/`shareIn`. Flint's release minification is
currently disabled. The full current unit gate and actual connected Android flow are required
after the change; earlier tests against 1.11.0 do not establish the new build's behavior.

## Separate existing toolchain limitation

The Gradle wrapper remains 9.6.1. Kotlin's [published compatibility table](https://kotlinlang.org/docs/gradle-configure-project.html)
lists Gradle through 8.12.1 and AGP through 8.7.2 for Kotlin 2.1.21. No wrapper failure was
observed, and the wrapper was not changed as part of this bounded metadata correction.
A successful gate is not a claim that every existing toolchain pin is within that matrix.

## Final readback

After the pin, actual `debugRuntimeClasspath` selects coroutines core **1.10.2** and Kotlin
stdlib **2.1.21**. `make verify-android` passed: debug assembly in 61 seconds and configured
unit variants in 34 seconds. That complete gate log contains **zero** Kotlin metadata
rewriting/parsing warnings. The results are summarized in
[`android-managed-2026-10-11/gate-summary.txt`](../android-managed-2026-10-11/gate-summary.txt).
Raw logs and dependency insight remain under ignored `android-app/build/opal-parity-baseline/`.
