# Opal Android 4.14.0 connected-service contracts

> **Historical research — original scope and status claims retained (2026-10-28).**
> Use the [v1 capability acceptance ledger](../acceptance/v1-capability-ledger.md) for
> current Flint implementation/evidence and [ADR-012](../architecture/protection-contract.md)
> for personal protection requirements. Old future-date, launch-count, pass, anti-removal,
> competitor and expanded connected-scope claims below are historical planning inputs,
> not current release promises. The Android connected backend remains debug-only;
> release is local-first, no-account and without analytics. No physical-device result
> follows from an old status column.

Investigated 2026-10-11 against the user-supplied `opal.apkm`. This is an evidence map for an independent Flint implementation. It does not establish a working clone of Opal's servers.

## Evidence boundary

The inspected source is standalone JADX output under `android-app/build/opal-analysis/decompiled/sources/`. All short source anchors below are relative to that directory. This directory is an ignored local analysis artifact, not Flint production source. The package identity, hashes, extraction commands, REA archive evidence, and decompiler limitations are recorded in [the main package report](05-opal-4.14.0-apkm-analysis.md). In particular, the REA Android provider was unavailable; these conclusions come from ordinary local source inspection, not REA Android evidence or observed network traffic.

No Opal API was called, no account was created, no invite was accepted, no push token was registered, and no purchase was attempted for this investigation. Account identifiers, credential values, embedded keys, and token values are intentionally excluded. Schema field names such as `token` identify contracts, not credentials.

The APK gives unusually strong member-side contracts. It cannot supply server authorization rules, admin applications, deployment infrastructure, payment configuration, production flags, or every server algorithm. Some decompiled control flow is malformed; affected conclusions below remain limited to clear callsites, serializers, or constants. Static callsites establish intended client behavior, not successful execution.

## Findings that change the reconstruction plan

1. Opal uses several connected systems: Firebase Authentication, Firestore documents, callable Firebase functions, app REST endpoints, FCM, and a RevenueCat/Superwall purchase flow. Reproducing all connected behavior needs a Flint-owned backend and provider configuration.
2. Managed-rule authoring is outside the recovered Android member contract. `r00/f.java:43–47` throws `UnsupportedOperationException` when asked to serialize a managed rule, with the explanation that the device never writes managed rules. Android downloads authority-authored rules and requests bypasses.
3. Uploading personal blocking activity is proven. General two-way cross-device restoration of personal rules, selections, schedules, and usage is **not** established by the inspected paths. Calling all of this “cross-device sync” would hide that distinction.
4. Account deletion is a request written to a backend document, not evidence that deletion has finished. Streak calculation and aggregated focus time are also server responsibilities.
5. Family, school, device-heartbeat, school-usage, referral, and gemstone flags exist. Their effective production values and rollout eligibility are unknown (`ax/a.java:20–31`). Presence of a DTO or flag does not prove a reachable screen.

## Account identity and profile lifecycle

| Feature | Authority visible in the APK | Contract status |
|---|---|---|
| Identity/profile | Firebase Auth + Firestore/callable profile creation | Concrete client calls recovered; server account policy unknown |
| Group membership/invites | App REST member endpoints | Reads/accept/leave recovered; admin creation/invite issuance unknown |
| Managed schedules/sessions | Remote authority supplies device read model | Download schema recovered; Android serializer explicitly rejects writes |
| Bypass allowance | REST result supplies bypass and remaining quota | Payload/state recovered; server approval/quota algorithm unknown |
| Rule change delivery | FCM invalidation → local sync worker | Concrete handler/retry recovered; live delivery unobserved |
| Personal history/sync | Callable activity uploader + local database | Upload recovered; general two-way personal-rule restore unproven |
| Streak/rewards | Callable functions + Firestore + REST | Client contracts recovered; eligibility/anti-abuse unknown |
| Subscriptions | RevenueCat purchases/customer info + Superwall UI | Purchase/restore/entitlement projection recovered; live configuration unknown |

| Client behavior | Observed transition or contract | Source |
|---|---|---|
| Anonymous session | Firebase anonymous sign-in returns an auth user; an existing anonymous user can be reused. | `d1/q6.java:126–167` |
| Email account | Separate callable branches link email/password to the current user, sign in with email/password, and create a new email/password account. Linking requires a current user. | `kp/f.java:67–153` |
| Password reset | Calls Firebase `sendPasswordResetEmail`; Firebase errors become domain auth errors. | `kp/j.java:88–141` |
| Google account | Silent and interactive Google credential flows feed the common Firebase credential sign-in helper. | `kp/o.java:94`, `kp/o.java:259–292` |
| Phone account | Phone credential can sign in, link to the current user, or update the current user's phone; link/change fail without an authenticated user. The app refreshes the auth-user state afterward. | `kp/t.java:54–136`, `kp/t.java:165–226` |
| Anonymous upgrade | Common credential helper tries linking when the current user is anonymous; a credential collision falls back to sign-in. It distinguishes a new-account result from an existing-account result. | `bc/g.java:1320–1413` |
| Auth-user shape | `userID`, `email`, `phoneNumber`, `isEmailVerified`, `isAnonymous`. | `op/e0.java:25–68` |
| App profile creation | Calls `createNewGem` with an empty map; reads `identifier`, `gemname`, and `creationDate`. If a profile already exists, the flow fetches the Firestore user. | `tk/s.java:628–657`, `tk/s.java:739`, `tk/s.java:855–880` |
| Profile edits | Firestore `users/{userId}` stores install/update date, age estimate, goal, occupation ID, and stated daily screen-time seconds plus report time. Display-name update also calls Firebase profile update. | `tk/s.java:200–250`, `tk/s.java:269–341` |
| Local profile projection | Remote create/get output is mapped and written into local user state; local Room user flows drive consumers. | `q20/l.java:33–43`, `q20/l.java:47–143` |
| Deletion request | Writes `accountRequests/{userId}` with `userId`, `data.action = deleteAccount`, and server `creationDate`; absence of an authenticated user fails. Completion of the write is logged as request submission. | `v2/t0.java:472–515` |

The REST interceptor obtains an auth token and an AppCheck token, adds `Authorization: Bearer …` and `X-Firebase-AppCheck`, and can proceed without a token if retrieval fails (`ty/d.java:27–52`, `ty/b.java:46–51`). The authenticator attempts one refresh for a 401 with no prior response, then stops retrying (`ty/e.java:29–53`). Actual server acceptance of missing, expired, forged, or incorrectly scoped tokens is unknown; it must not be inferred from the interceptor.

Not established here: email verification delivery/confirmation, identity-provider linking edge cases after process death, full sign-out cleanup, account merge of personal rules, actual deletion timing/retention, or Apple's availability as an Android sign-in option.

## Recovered REST surface

HTTP methods are confirmed by the Retrofit annotation parser: `g60.b → DELETE`, `f → GET`, `o → POST`, `p → PUT` (`e60/t0.java:564–575`). These are path contracts only; production base URLs and credentials are unnecessary for a Flint reconstruction.

| Method and path | Input | Response model | Source |
|---|---|---|---|
| `GET api/v1/me/groups` | Optional/query `subject_type` string | List of group summaries | `o00/a.java:19–20` |
| `POST api/v1/groups/invites/{code}/accept` | Invite code in path | Membership | `o00/a.java:22–23` |
| `POST api/v1/groups/{id}/leave` | Group ID in path | Unit/empty domain result | `o00/a.java:16–17` |
| `GET api/v1/schools/{schoolId}` | School ID in path | School | `u00/c.java:12–13` |
| `GET api/v1/schools/invites/{code}` | Invite code in path | School invite preview | `u00/c.java:15–16` |
| `GET api/v1/me/managed-rules` | Authenticated identity | List of decoded managed rules | `q00/a.java:25–26` |
| `GET api/v1/me/managed-rule-bypasses` | Query `startAt`, `endAt` | List of bypasses | `q00/a.java:19–20` |
| `GET api/v1/me/managed-rule-bypasses-settings` | Authenticated identity | Self-bypass settings | `q00/a.java:22–23` |
| `POST api/v1/me/managed-rule-bypasses` | Self-bypass request | Bypass plus updated self-bypass status | `q00/a.java:16–17` |
| `PUT api/v1/push/tokens` | Device ID, FCM token, platform, app version | Unit/empty domain result | `s00/a.java:15–16` |
| `DELETE api/v1/push/tokens/{deviceId}` | Device ID in path | Unit/empty domain result | `s00/a.java:12–13` |
| `POST api/v1/events` | One heartbeat or a batch of distracting-usage events | Empty result or batch results | `j00/a.java:15–19` |
| `GET api/v1/gemstones` | Query `locale`, `page`, `perPage` | Gemstones plus pagination | `l00/a.java:21–22` |
| `GET api/v1/gemstones/unlocked` | Authenticated identity | Unlocked gemstone IDs | `l00/a.java:18–19` |
| `POST api/v1/gemstones/unlocked` | `gemstoneId` | Unit/empty domain result | `l00/a.java:15–16`, `n00/g.java:23–24` |

### Groups, school membership, and invitation schema

| Object | Serialized field names | Evidence |
|---|---|---|
| Group summary | `id`, `subject_type`, `name`, `parentGroupId`, `role`, `joinedAt`, `memberCount` | `p00/e.java:25–32` |
| Group | `id`, `subject_type`, `name`, `parentGroupId`, `members`, `subGroups`, `createdAt`, `updatedAt` | `p00/c.java:24–32` |
| Membership | `id`, `groupId`, `userId`, `role`, `joinedAt` | `p00/l.java:24–29` |
| Roles | Wire values `admin`, `member`, `restricted` | `p00/k.java:51` |
| Generic invite preview | `groupId`, `groupName`, optional `parentGroupName`, `invitedRole`, `isShared`, optional `inviter`, `targetEmail`, `targetPhone` | `p00/g.java:25–33` |
| Inviter | `displayName`, optional `avatarUrl` | `p00/i.java:23–25` |
| School invite preview | `groupId`, `invitedRole`, `isShared`, optional `targetEmail`, `targetPhone`, required `school`, optional `student` | `v00/g.java:28–35` |
| Invited school | `name`, `shortName`, optional `logoPath` | `v00/a.java:26–29` |
| Invited student | `firstName`, `lastName` | `v00/c.java:25–27` |
| School | `id`, `shortName`, optional `logoPath`, `timezone` | `v00/e.java:26–30` |

Deep-link parsing recognizes `/join-school`, `/invite-friend`, and `/qr` with corresponding app links; these are distinct entry paths (`oy/j.java:21–43`). A QR focus session is not automatically a group invitation.

There are serializers for family creation (`name`, default `subject_type = family`), group rename (`name`), and membership role update (`role`), but no matching app REST interface endpoints were found in the bounded annotation inventory above (`p00/a.java:23–25,69–74`; `p00/o.java:23–24`; `p00/q.java:23–24`). Their presence is structural evidence. It does **not** establish an Android family-administration workflow or server CRUD paths.

### Managed-rule schema and interpretation

Managed rules use the JSON discriminator `ruleType`, with `recurring_schedule` and `session` variants. Both serialize `id`, optional `name`, `targetGroupId`, `scope`, `config`, `createdAt`, and `updatedAt` (`com/withopal/opal/remote/rest/managedrule/dto/b.java:14–15`; `…/c.java:26–33`; `…/e.java:27–34`).

| Field/object | Wire shape or behavior | Source |
|---|---|---|
| Rule scope | `kind` and optional `userId`; `group` applies at group scope, `member` requires `userId`; unknown scope kind throws. | `r00/m.java:22–24`, `q00/b.java:51–69` |
| Block scope | `type`; known values `all_apps` and `distracting_apps`. Unknown type is retained as an unknown domain variant. | `r00/a.java:22–23`, `q00/b.java:39–45` |
| Recurring config | `blockScope`, `startTime`, `endTime`, `weekdays`, optional `unblocks`. Times parse as `LocalTime`; weekday integers map through ISO `DayOfWeek.of`. | `r00/i.java:25–30`, `q00/b.java:70–88` |
| Session config | `blockScope`, `startTime`, `endTime`, optional `unblocks`. Mapper parses finite start/end timestamps. | `r00/v.java:24–28`, `q00/b.java:89–95` |
| Unblock policy | `allowed` boolean; missing `unblocks` maps to a third unspecified state, distinct from true and false. | `r00/x.java:21–22`, `q00/b.java:135–148` |
| Decode failure | Per-rule JSON decode can return an unreadable-rule object carrying an error, rather than throwing the entire list's parsing away. Subsequent application behavior needs separate validation. | `r00/f.java:21–35` |
| Write boundary | Managed-rule serialization throws; device-side authoring is explicitly absent from this serializer. | `r00/f.java:43–47` |

The active engine already has separate personal/managed rule evaluation and always-allowed selections, and merges their resulting decisions. The [rule evidence report](opal-4.14.0-rules-notes.md) traces those enforcement semantics. Membership and downloaded authority rules must be represented separately from a user's ordinary editable rules in Flint.

### Self-bypass contract

A request contains `groupId`, `reason`, `comment`, `startDateTime`, and `endDateTime` (`r00/k.java:23–28`). Wire reasons are `feeling_sick`, `medical_appointment`, `family`, `schedule_change`, `wrong_schedule`, and `other` (`p00/k.java:108`; enum identities also in `SelfBypassReasonDto.java:36–49`). `q00/h.java:235–258` maps the reason and formats the local date/time values before sending.

The successful response supplies both `bypass` and `selfBypass` (`r00/p.java:23–25`). The client maps both into the returned domain result after a successful HTTP result; malformed data produces a managed-rule response error (`q00/h.java:269–290`). A UI should update the allowance and active bypass from this returned authoritative result, not decrement a displayed counter merely because the user tapped a button.

The bypass contains `id`, `source`, `bypassesPersonalRules`, `startDateTime`, `endDateTime`, optional `reason`, and optional `name` (`r00/g.java:22–29`). `source == self` marks a self-request; times parse as `LocalDateTime` (`q00/b.java:97–110`). Status has `groupId`, `enabled`, `remaining`, `limit`, `period`, `resetsAt` (`r00/t.java:23–29`). Known period values are `week` and `month`, with an unknown-period variant (`q00/b.java:113–132`). Settings can have absent `selfBypass` (`r00/r.java:22–23`).

Unknown: minimum/maximum duration, comment validation, whether the school approves requests, authority-created bypass behavior, exact reset timezone, concurrent-request handling, and how policy differs by group type. The response contract proves a returned bypass; it does not prove that every request is automatically approved or establish quota algorithms.

## Push, local cache, and synchronization

`OpalFirebaseMessagingService` reads string data fields and looks at `type`. The explicit application handler for `managed_rules.updated` schedules a `SchoolSyncNowWorker`; `debug`, `wake`, and unknown push types are classified separately (`…/push/service/OpalFirebaseMessagingService.java:89–114`). Push is an invalidation signal: the shown handler schedules a fetch, rather than treating a pushed rule body as an authoritative rule.

The scheduler enqueues unique work named `school-sync-now` (`f10/a.java:28–40`). The worker calls school synchronization with trigger `Push`; faults retry, and five attempts produce failure (`…/school/domain/work/SchoolSyncNowWorker.java:59–92`). A deadline worker calls `syncSchoolIfStale` (`…/SchoolSyncDeadlineWorker.java:58–83`); its helper has a 24-hour stale threshold (`h10/q.java:36–38,116–129`). The full school-sync method has substantial JADX control-flow damage, so exact transaction ordering and all failure policies remain unresolved (`h10/s.java`).

Local storage explicitly includes `groups`, `group_memberships`, `schools`, `managed_rule_details`, `managed_rule_bypasses`, and ordinary rule tables (`gb/r.java:499–504,512`). A `RemovedBySchool` branch exists in the synchronization method (`h10/s.java:937,1231,1499`), but the damaged decompilation is insufficient to specify all deletion/reconciliation behavior.

FCM token changes schedule registration (`…/OpalFirebaseMessagingService.java:138–147`). Registration payload has `deviceId`, `token`, `platform`, `appVersion` (`t00/a.java:25–29`), with platform fixed to `android` in its convenience constructor (`RegisterPushTokenRequest.java:59–66`). Registration work gives up after five attempts (`…/push/work/PushTokenRegistrationWorker.java:68–75`). Token values must never be treated as user identity or echoed in Flint UI/logs.

### Proven data upload, and what “sync” does not prove

Foreground entry and blocking-rule mutations trigger a block-activity synchronizer; it uses an `android-`-prefixed device ID (`vp/a.java:72–110`; `vp/d.java:84–91`). The synchronizer retains a baseline and uploaded instance IDs, has a seven-day constant, and explicitly skips when the clock moves backward (`jo/e.java:73–75,160–164,263,432–434`). Precise history-window behavior needs DEX/runtime validation because this method has damaged decompiler flow.

The upload provider calls `saveBlockActivity` with `blockActivity: [...]`. Each item carries `id`, `deviceId`, `entityType`, `creationDate`, `startDate`, `endDate`, `localStartDate`, `localEndDate`, `attribution`, and `activity`; the response can list `failedInserts` containing `id` and `errorMessage` (`xz/b.java:60–65,79–109`). Rule-derived `entityType` values include `session`, `schedule`, `appLimit`, and `appLock`; activity shapes include schedule/difficulty/selection and usage/open-limit configuration (`jo/e.java:607–742`).

This establishes activity upload and partial-failure handling. It does not establish that logging in on a second device restores personal rules, installed-app selections, categories, screen-time history, or device permissions. A Flint two-way personal-rule sync service would be a deliberate product implementation beyond the evidence of this uploader.

### Managed-device status and usage events

The REST event envelope serializes `schemaVersion`, `eventType`, `eventId`, `emittedAt`, `userId`, `deviceId`, `meta`, and `data` (`k00/l.java:24–31`; `DeviceEventRequest.java:44–53`). Metadata has `appVersion`, `platform`, `osVersion`, `source`; the convenience constructor supplies `android` and `main_app` (`DeviceEventMeta.java:63–67`). Emission time is truncated to seconds (`…/t9.java:915–926`).

Two concrete event types are wired:

- `device.heartbeat`: `data.monitoringStatus` is `active` or `denied` (`…/t9.java:1829–1839`, `k00/m.java:23–24`). A periodic worker is scheduled at a two-hour interval when its enabling flow is true (`aw/c0.java:545–560`); the worker permits three attempts (`…/deviceevents/work/DeviceHeartbeatWorker.java:85–89`). This reports monitoring state, not guaranteed uninterrupted enforcement.
- `activity.distracting_usage`: `data` carries `managedRuleIds`, `personalRuleIds`, `observedAt`, `distractingUsageSeconds` (`k00/a.java:25–29`; `…/t9.java:1728`). Batch request is `{events:[...]}`; response has `{results:[{index,status,eventId?,reason?}]}` (`k00/d.java:23`, `k00/e.java:24`, `k00/g.java:25–28`). Local tables store an event outbox/cursor (`gb/r.java:493–495`). Accepted status values and all deletion/retry semantics are not established here.

Managed usage reporting is a connected product feature with meaningful privacy effects. A Flint reconstruction must disclose which data is shared with the selected family/school and tie sharing to membership/role rules. Copying unrelated marketing telemetry SDKs is unnecessary to provide the recovered functional contracts.

## Streaks, statistics, gems, and referrals

| Area | Contract and client evidence | Unknown server behavior |
|---|---|---|
| Current streak | Callable `getCurrentStreak({deviceId, force:false})`; response must have numeric `streak`. Missing/malformed values throw. `…/t9.java:1127–1149`. | Qualifying day, time-zone boundaries, grace days, cross-device aggregation, repair policy. |
| Aggregated focus time | Callable `getCurrentBlockStatistics({deviceId, force:false, minimumComputationDelay:900.0, createIfMissing:true})`; reads `focusTime`. `…/t9.java:998–1018`. | The aggregation algorithm, overlapping blocks, exclusions, freshness guarantees. |
| Profile gem | Callable `createNewGem({})`; reads profile identity/name/creation date. `tk/s.java:628–657`. | Name uniqueness and generation algorithm. |
| Gem catalog | REST catalog rows include `id`, optional `unlockRequirement`; pagination has `totalPages`. Unlock requirement includes `type`, optional `requiredInvites`. `n00/a.java:25–27`, `n00/c.java:24–26`, `n00/e.java:23–24`, `n00/i.java:24–26`. | Exact eligibility and validation of claimed unlocks. |
| Catalog presentation | Rich catalog rows also encode slug/names/descriptions, milestone ID, category, visibility, colors/assets, prevalence, and creation/update dates. `m00/a.java:30–46`, `m00/n.java:23–24`. | Which rows/assets are served live and their availability rules. |
| Gem unlock | Fetch unlocked IDs; record a `gemstoneId` through REST. `l00/a.java:15–22`, `n00/g.java:23–24`, `n00/k.java:23–24`. | Whether server checks eligibility or only records client-computed awards. |
| Referral code lookup | Firestore `referralCodes` queried by `userId` or `code`; multiple codes can be queried. `…/t9.java:950,1044,1174`. | Rate limits, uniqueness, fraudulent/self referral prevention. |
| Referral attribution | Writes `invites/{userId}` containing `userId`, `invitedByUserId`, `installDate`, `hasUpgraded:false`, `hasSignedUp:true`, `referralCode`. `…/t9.java:1927`. | Eligibility, attribution precedence, anti-abuse enforcement. |
| Referral redemption | Writes reward records with `id`, `redemptionDate`, `updateDate`, `rewardSource:referral`, `rewardSourceData:{id,type}`, `userId`, and either `data:{startDate,endDate}` or `{isHandled:false}`. `…/t9.java:1558–1573`. | Actual subscription grant, fulfillment, idempotency, retraction. |

Here `…/t9.java` means `com/google/android/gms/internal/p000firebaseauthapi/t9.java`: R8 merged app responsibilities into this misleading package/class. These callsites are application evidence despite that location; ordinary Firebase SDK implementation alone would not prove the application uses a feature.

## Subscription, paywall, and restore lifecycle

The app has explicit entitlement identifiers `premium_tier_1`, `premium_tier_2`, `schools_pro`, and a staging school identifier (`z10/c.java:20–28`). The first two have one boolean flag and school entries the other; this is not by itself a complete feature matrix.

`mn/q.java:845–936` converts RevenueCat customer information into either:

- `Subscribed(entitlement, source, productID, expirationDate, willRenew, memberSince)`; or
- `NotSubscribed(hasSubscribedBefore)`.

The mapped source is Play Store, promotional, or elsewhere (`z10/d.java:24–30`). Active entitlements are inspected in a sorted preference order (`w10/g.java:22`; comparator `mg/r2.java:65`). Historical entitlement data influences the `hasSubscribedBefore` result. Prices are obtained from offerings; the data source explicitly handles offerings failure by showing no price (`u10/i.java:144`). Current prices cannot be recovered from these identifiers.

Superwall is identified with the app user ID and RevenueCat login receives the same identity (`w10/g.java:91–106`; `u10/i.java:150–187`). On reset, Superwall returns to anonymous and the RevenueCat data source logs out (`b20/a.java:31–46`, `u10/i.java:191–227`). These are relevant to account transitions and avoiding one user's entitlement on another user's session.

The app's `x10/g` implements Superwall `PurchaseController` and delegates purchase execution to RevenueCat. It handles missing products/options and maps failure/cancellation results (`x10/g.java:41–66,124–164,421–431`). The restore path updates the paywall's active entitlement set, then returns `Restored` only if active entitlements exist; an empty result returns a failure (`x10/g.java:171–173,182–227`).

Web purchase redemption is also present. Domain outcomes include succeeded with active-entitlement count, failed with error code, invalid token, expired, belongs to another user, and unreadable (`z10/o.java:7–28`, `z10/i.java` through `z10/n.java`). The coordinator waits around identity changes and logs the case where redemption outlasts the identity wait (`v10/c.java:180–185`). Server verification, purchased-product configuration, payment processing, refund/revocation delivery, and the actual paywall experiment configuration are not available in this APK.

## Proposed Flint contracts

This section is a design recommendation, **not recovered Opal source**. It gives the parent implementation a minimal coherent service boundary; it does not authorize deployment, billing purchases, or invitations to real people.

Flint's current `AppGroup` means a saved app/domain selection. Keep that separate from remote household/school `Group` membership. Current `BlockRule` has no authority, membership, revision, or server-policy metadata, so inserting remote rules into its ordinary editable list would be incomplete. Relevant existing files: `android-app/core/core-model/src/main/kotlin/com/flint/peakfocus/core/model/Models.kt` and `android-app/core/core-datastore/src/main/kotlin/com/flint/peakfocus/core/datastore/GroupsStore.kt`.

| Boundary | Minimum Flint-owned contract | Required semantics |
|---|---|---|
| Identity | Sign up/in/out, current user, refresh/revoke session, profile update, deletion request/status; provider adapters for Google and phone | Server derives actor from verified session. Anonymous-to-account migration has an explicit policy. Never trust a payload `userId` as authorization. |
| Devices | Register/list/remove devices, optional push registration, last successful sync/heartbeat | Device belongs to exactly the authenticated user; token rotation and sign-out invalidate old ownership. Show stale/unknown monitoring state honestly. |
| Groups | Create household/school, get/list/rename group, role transitions, member removal/leave, preview/create/revoke/accept invitations | Separate admin/member/restricted roles; server checks every operation. Invite acceptance is scoped, expiring, revocable, and atomic. These admin routes are Flint design, not recovered Opal paths. |
| Authority rules | Admin CRUD for versioned schedule/session rules plus `GET me/managed-rules` snapshot | Include authority/group, target scope, timezone, enabled/deleted status, monotonic revision, server update time. Apply a complete snapshot transactionally; local personal-rule editor cannot modify managed records. |
| Bypasses | Fetch settings and active intervals; submit reason/comment/interval; return created bypass plus updated quota | Server validates membership, quota, interval, and reason atomically. Include whether personal rules may be bypassed. Never let a personal emergency pass silently bypass a school's rule unless policy grants it. |
| Personal sync | Separate optional versioned rule/profile sync with tombstones and conflict policy | New Flint feature beyond proven activity upload. Sync only portable configuration; device permissions and installed apps require reconciliation per device. |
| Activity outbox | Idempotent event ingestion with stable event ID, source device, event schema, observed/emitted times; per-item acceptance | Retry failed items only. Scope managed usage visibility to authority policy. Server recomputes summaries and validates identity. |
| Streak/rewards | Fetch aggregate progress/catalog/unlocks; claim eligible reward | Publish Flint's qualifying-day algorithm. Server calculates eligibility from trustworthy events and makes claims idempotent. Do not assume Opal's client reward write proves sufficient authorization. |
| Entitlements | Fetch offerings/current entitlements, verify Play purchase, restore/reconcile, consume billing provider notifications | Provider-backed verification controls state. Sandbox/free/development entitlements must be visibly distinguishable from real purchases. RevenueCat/Superwall are adapters, not a substitute for configured products. |
| Change delivery | Push invalidation carrying type/revision, plus polling/foreground refresh and durable retry | Fetch authenticated server truth after invalidation. Enforcement uses the last valid local snapshot during outages; define expiration and fail-safe behavior explicitly. |

A useful local implementation can run a Flint-owned service on the developer machine, use two independent client identities, and demonstrate invite acceptance, admin rule update, member rule download/enforcement, bypass grant/quota change, and reconnect recovery. It must not label a local mock as deployed connected-service parity. Provider-backed sign-in, FCM delivery, real subscriptions, institutional administration, and multi-device runtime validation remain separate completion gates.

## Investigation checklist

| Question | Status |
|---|---|
| Android account providers and local identity shape | Answered for anonymous/email/Google/phone; some account lifecycle edges remain partial. |
| REST authentication attachment and retry | Answered at client level; server validation unknown. |
| Group/school invitation and membership fields | Answered at client contract level; all admin routes and effective policy remain unknown. |
| Managed-rule read model, scope, schedule/session formats | Answered; authority-side CRUD is outside the recovered client. |
| Bypass payload, response, and allowance fields | Answered; approval and quota algorithms unknown. |
| Push invalidation and worker retry | Answered for managed-rule update and token registration. |
| School reconciliation transaction and stale-cache policy | Partial; damaged decompilation requires DEX/runtime follow-up. |
| Personal-rule cross-device restoration | Unresolved; activity upload is verified, restoration is not. |
| Streak/focus/gem/referral client contracts | Answered for listed callsites; server algorithms and anti-abuse unknown. |
| Purchase, restore, identity, and entitlement projection | Answered at client integration level; live product/provider configuration unknown. |
| Live connected-service behavior | Unresolved; no authorized service interaction was performed in this evidence task. |
