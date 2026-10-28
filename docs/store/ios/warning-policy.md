# iOS warning policy

All XcodeGen-owned Swift targets inherit `SWIFT_TREAT_WARNINGS_AS_ERRORS=YES`
in Debug and Release. CI builds and tests those configurations, so a new
Swift compiler warning in the app, extensions or Xcode test targets fails its
compile. Swift language mode remains 5 and the minimum iOS version remains 16.

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
