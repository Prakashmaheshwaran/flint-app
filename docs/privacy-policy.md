# Flint privacy policy

Effective date: October 28, 2026

This policy covers the local-only release versions of Flint for iOS and Android.
Flint is a personal focus app maintained by the Flint project. Contact the
maintainers through [Flint support](https://github.com/Prakashmaheshwaran/flint-app/issues).

## Your data stays on your device

Flint does not send your personal data to developer servers or analytics or
advertising services. It has no accounts or cloud sync. Local system-display
features and external support choices are described below. You can
use the app without registering. The Android release does not request network
access. Experimental developer builds and their local test services are outside
this policy and are not included in the release app.

Flint saves the rules, app groups, website choices, schedules, limits, protection
preferences and session information needed for your focus features on your
device. Local state can include usage/open counters, break or pass use and focus
history. This information is used to apply your choices and display local
summaries. It is not sent to the developers.

## Local focus history and visible reminders

Flint keeps a local focus-session event history for Today, completion and Insights.
It can contain session/operation identifiers, names you enter, protection level, start
and end times, outcome, origin, and available break/intercepted-open or coverage information.
This focus log does not store selected app/package identifiers, Screen Time tokens,
website domains, raw browsing URLs or Apple's usage-report values. Screen usage and
focus-session totals are separate; missing information is not a measured zero.

Focus history uses a rolling 400-day retention target with bounded local storage;
size limits can remove older records sooner. Active session and bounded recovery records
may be kept so interrupted writes or unreadable history can be handled safely.
The in-app clear-history control clears the displayed historical session log while
preserving an active session. It does not cancel protection, reset quota/pass use,
or promise to erase every local recovery/diagnostic copy or notification bookkeeping record.
Use the OS app-data/deletion controls below for the broader local-data removal route.

If enabled, focus-end reminders use the planned end time; they do not prove a completed
or uninterrupted focus session. Widgets, notifications and the opt-in iOS Live Activity
may display names you choose, protection level, end/next-time estimates, observation time
and local focus summaries. The launcher and operating system can retain these visible
copies until refreshed or removed. The optional iOS app PIN does not promise to hide
those OS surfaces; review notification and lock-screen settings and remove or disable
displays you do not want. Planned-end reminders use generic text rather than session names.
No account or developer upload is added by these features.

## Android permissions and local processing

With your Accessibility consent, Flint identifies the app in the foreground and
reads the address bar in supported browsers to compare the current app or website
with your rules. URLs are processed locally for that decision; Flint does not
save or upload a raw browsing-history log. Browser support depends on an
accessible address bar and can change when browsers update.
While a website rule is active, Flint also re-reads supported browsers' address bars
when a page changes within a tab; other apps' page-change signals are ignored without
reading their content.

See the Known adapters browser list on the You health screen in Flint. Address-bar
URLs stay on this device, in memory for blocking; they are not saved as browsing
history. Websites you enter remain in your rules. Address-bar reading stops when Accessibility is off.

With Usage Access, Flint reads Android's on-device app-usage events and duration
to calculate local reports and limits and provide app-only fallback detection.
The app picker reads launchable app names, package identifiers and icons so you
can choose which apps to include in rules. Selected apps and website domains
remain in your saved preferences until you change or remove them.

Display-over-other-apps access supports the fallback blocking screen. A
foreground service and notifications provide background focus functionality and
visibility; optional battery settings can reduce interruptions. These
permissions do not send data to Flint. You choose whether to grant special
access and can revoke it through Android Settings. Declining or revoking access
can limit related blocking, website or reporting features.

## iOS Screen Time and local storage

Flint uses Apple's Family Controls, Device Activity and Managed Settings APIs
when you authorize Screen Time access. Selections made in Apple's Screen Time
picker use privacy-preserving tokens. If you type custom Safari allow/block
domains, Flint stores those domain strings locally in its shared app-group
preferences. Flint stores your selections, configured
rules and session state locally in its app and shared app-group storage, which
lets its own Screen Time extensions apply your choices. Local usage information
is used for the features shown on your device and is not uploaded to Flint.

If you set Flint's optional app PIN, a salted hash and salt are stored locally
to check it; the PIN is not sent to a server. Apple's Screen Time permissions,
system passcode, device backups and system usage records are controlled by iOS
and your Apple settings.

Apple's usage report is rendered within Flint's report extension and is not exported
to the host focus-history log or a Flint service. Flint's widgets read only local
status/history summaries. iOS Live Activity is optional and defaults off.

## Retention and deletion

Your saved rules and preferences remain on the device until you edit/delete
them or remove the app's stored data. Counters, temporary access and history
change or expire according to the feature that uses them. Flint has no server
copy to restore if local data is lost and no cloud account to delete.

On Android, open Android Settings, select Flint, then use its Storage controls
to clear app data, or uninstall Flint. Clearing Flint does not erase usage
records maintained by Android itself. The Android app requests that application
backup be disabled; device-maker transfer behavior is controlled by the system.

On iOS, use Delete App, rather than Offload App, to remove Flint and its local
app data. Review Apple's Screen Time and backup settings separately for
system-managed records or backups; deleting Flint does not delete those records
from Apple's system services or an existing device backup.

## Other services and changes

If you choose to contact support or open an external website, that service
handles the information you provide under its own policy. Flint does not
automatically attach your local rules, app inventory, usage or browsing data.
Support issues posted on GitHub are public; do not include personal app selections,
browsing information or other sensitive data in an issue.

We will update this page if Flint's data practices change and show a new
effective date. New data uses requiring permission will be explained before
you choose to enable them.
