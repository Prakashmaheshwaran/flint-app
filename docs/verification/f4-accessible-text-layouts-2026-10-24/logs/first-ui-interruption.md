# Interrupted first UI run at dec9530

- Both platform gates passed. The installed executable/dylib/APK hashes are in `logs/ui-tested-binaries.txt`.
- `ios-after-large.xcresult`: `LayoutTests/testLayouts()` failed with `Test crashed with signal term.` The target is `FlintAuditUITests`; no product assertion failure was reported. The case reached You tab assertions before termination. It is not counted as a pass.
- The same result records `testPermissionErrorLayout()` passed in 37.181 seconds after XCTest restarted its runner.
- Host CoreSimulator device system.log records a syslogd restart at 01:05:08. Host CoreSimulator.log records runtime/service reloading at 01:05:07. These coincide with the interrupted case, but do not establish the trigger.
- At 01:06:40 the next font-setting command failed with CoreSimulator error 405, `Unable to lookup in current state: Shutdown`. The shell stopped with exit 149 (405 modulo 256). Its first cleanup command failed for the same reason, so this run does not prove cleanup.
- No Flint or FlintAudit crash report was found in the host DiagnosticReports folder. Host swap pressure is context, not a proven termination cause. No unrelated user process was stopped.
- Preserve the failed result and screenshots. UI-only retry boots the same existing simulator, rechecks live PIN absence, verifies the already-passing binary hashes, reruns only the terminated Large case and all unrun AX5/Android/narrow cases. Assertions remain unchanged. Cleanup records each result and continues after individual command errors.
