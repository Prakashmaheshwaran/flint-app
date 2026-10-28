# iOS warning policy

All XcodeGen-owned Swift targets inherit `SWIFT_TREAT_WARNINGS_AS_ERRORS=YES`
in Debug and Release. CI runs Debug builds/unit tests; the separate D3 Release
gate compiles Release without signing. A new Swift compiler warning in the app,
extensions or Xcode test targets fails its compile. Swift language mode remains
5 and the minimum iOS version remains 16. Local builds require Xcode 15.3+
(Swift 5.10+) so `MainActor.assumeIsolated` back-deploys to iOS 16. The
[Swift 5.10 back-deployment change](https://github.com/swiftlang/swift/pull/70474)
is the reason for this compiler prerequisite; it does not raise the device OS
minimum or enable Swift 6 language mode. CI uses its current macOS runner Xcode.

`AppLockWindow`'s scene observer uses NotificationCenter's explicit `.main`
queue. `MainActor.assumeIsolated` makes this existing executor guarantee
explicit and preserves synchronous privacy-curtain rendering before UIKit
snapshots. Scheduling a new asynchronous Task here could delay that rendering.
The Focus Filter suggestion is a `let` because its parameter wrappers allow
their existing nonmutating property setters; behavior is unchanged.

Xcode build-tool diagnostics are outside this Swift compiler setting. In
particular, App Intents metadata extraction can report that no AppIntents
framework is linked for extension targets that define no intents. Failing CI
on every English `warning:` log line would also include Apple SDK and runner
tool output and would be brittle across Xcode updates. We therefore enforce
compiler warnings for owned targets rather than a global text filter. The
local FlintCore Swift package is a separate SwiftPM graph; this project setting
does not promise to turn every dependency diagnostic into an error.

Hosted runner SDKs can differ from the local SDK. For example, Xcode 26.6
reports a known `ShieldAction.firstSecondarySubmenuItemPressed` case absent
from older switch lists. The shield adapter deliberately handles every action
other than its supported primary/secondary buttons with the existing `.none`
response, so its explicit `default` preserves behavior across SDK case lists
without granting access. This is not a new submenu action implementation.
When a runner SDK exposes another source diagnostic, the owning lane must
triage and fix it promptly (lane B owns shield/enforcement adapters). Do not
silently turn off the compiler gate to hide SDK-driven warnings; check both
the runner diagnostic and the local SDK before choosing the smallest fix.
