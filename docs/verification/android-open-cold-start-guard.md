# Open configuration cold-start guard

Each Android coordinator/detector instance remembers its last readable Open-limit
configuration in memory. If configuration becomes unreadable after a successful read,
known limited targets remain protected and known unlimited targets remain usable.
If it is unreadable from coordinator initialization, the unknown-target guard lasts five
seconds and then wakes stationary detectors. A previously limited app may therefore
become accessible after roughly five to six seconds, until storage becomes readable
and enforcement can reconstruct its limit; each new coordinator instance starts a
new five-second guard, including accessibility re-initialization, Path B handoff
recreation, or process restart. Other independently known protection still applies. This is the
intentional tradeoff that avoids indefinite device-wide lockout when no target
configuration can be recovered, not proof that those targets have no Open limit.

During that initial guard the temporary OPEN_LIMIT/HARDCORE cause offers no ordinary
or credit exit, including for a target with a known EASY rule. Safety-floor targets
remain exempt. The last-good cache and initial deadline are not durable rights or
quota records and do not reset counters. JVM regressions cover the bounded unknown
guard, expiry wake, known-limited and known-unlimited behavior; real-device storage
failure and OEM timing remain unverified.
