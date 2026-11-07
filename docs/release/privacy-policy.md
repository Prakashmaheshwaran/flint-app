# Flint Privacy Policy

_Last updated: 2026-11-01_

Flint is a focus and screen-time app for iPhone, iPad, and Android. This policy describes what
the app does with your information. It is deliberately short because Flint has no account
system, no analytics, and no server.

**Contact:** `SET BEFORE PUBLISHING — support email`
**Source code:** https://github.com/Prakashmaheshwaran/flint-app

## The short version

- Flint does not collect, transmit, sell, or share personal data.
- There is no Flint account, sign-in, or cloud sync.
- Flint has no analytics, advertising, crash-reporting, or third-party SDKs.
- Everything you configure stays on your device. Deleting the app deletes it.

## What Flint accesses, and why

All of the following is processed **on your device only**. None of it leaves the device.

**iPhone and iPad (Apple Screen Time API).**
With your permission, Flint uses Apple's Family Controls and Device Activity frameworks to let
you choose the apps and websites you want to block, to apply block screens on a schedule or
after a time limit, and to show your own usage summary. Apple's frameworks keep app choices
opaque to Flint where possible, and the selection is stored in a private App Group container on
your device. Flint shows Apple's system permission prompt before any of this begins, and you can
revoke Screen Time access in iOS Settings at any time.

**Android (usage access, accessibility service, overlay).**
With your permission, Flint uses Android's Usage Access and Accessibility Service APIs to notice
when a blocked app comes to the foreground, and draws a block screen over it. Flint shows an
in-app explanation and asks for your explicit consent before sending you to Android settings to
enable the accessibility service. You can turn it off at any time in Android Settings →
Accessibility.

**Notifications and background services.**
Flint uses a foreground service on Android and background extensions on iOS so that blocking
keeps working when the app is closed. These show the required system notifications and do not
transmit data.

## What Flint does not do

- No data is sent off your device. The Android build does not request the `INTERNET` permission,
  and the iOS build has no networking code.
- No accounts, email addresses, names, contacts, photos, location, or device identifiers are
  collected.
- No behavioral profiles, no tracking across apps or websites, no advertising identifiers.
- No third-party libraries that collect data. Flint's only runtime dependencies are Apple's and
  Google's own platform libraries.

## Data retention and deletion

Your settings, blocks, schedules, and usage summaries live in Flint's private storage on your
device. Uninstalling Flint deletes them. There is no server copy to delete because there is no
server. You can also clear data from the app's settings or from Android's app-info screen.

## Children's privacy

Flint does not knowingly collect information from anyone, including children. Because no data is
collected, no personal information from a child is gathered or shared.

## Your rights (GDPR, CCPA, and similar)

Those laws give you rights over personal data held by a company. Flint holds none: nothing is
collected, so there is nothing to access, correct, export, or delete on a server. If you believe
otherwise, contact us at the address above.

## Changes to this policy

If Flint's data practices change — for example, if an optional cloud feature is added — this
policy will be updated before that feature ships, and the "Last updated" date will change.

## Contact

`SET BEFORE PUBLISHING — support email`

For security reports, see [`../../SECURITY.md`](../../SECURITY.md).
