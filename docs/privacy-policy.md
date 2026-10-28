# Flint privacy policy

Effective date: October 28, 2026

This policy covers the local-only release versions of Flint for iOS and Android.
Flint is a personal focus app maintained by the Flint project. Contact the
maintainers through [Flint support](https://github.com/Prakashmaheshwaran/flint-app/issues).

## Your data stays on your device

Flint does not collect, sell or share your personal data with its developers or
third parties. It has no accounts, analytics, advertising or cloud sync. You can
use the app without registering. The Android release does not request network
access. Experimental developer builds and their local test services are outside
this policy and are not included in the release app.

Flint saves the rules, app groups, website choices, schedules, limits, protection
preferences and session information needed for your focus features on your
device. Local state can include usage/open counters, break or pass use and focus
history. This information is used to apply your choices and display local
summaries. It is not sent to the developers.

## Android permissions and local processing

With your Accessibility consent, Flint identifies the app in the foreground and
reads the address bar in supported browsers to compare the current app or website
with your rules. URLs are processed locally for that decision; Flint does not
save or upload a raw browsing-history log. Browser support depends on an
accessible address bar and can change when browsers update.

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
when you authorize Screen Time access. Your app, category and website selections
use Apple's privacy-preserving tokens. Flint stores your selections, configured
rules and session state locally in its app and shared app-group storage, which
lets its own Screen Time extensions apply your choices. Local usage information
is used for the features shown on your device and is not uploaded to Flint.

If you set Flint's optional app PIN, a salted hash and salt are stored locally
to check it; the PIN is not sent to a server. Apple's Screen Time permissions,
system passcode, device backups and system usage records are controlled by iOS
and your Apple settings.

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

We will update this page if Flint's data practices change and show a new
effective date. New data uses requiring permission will be explained before
you choose to enable them.
