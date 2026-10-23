# Local managed rules contract (v1)

This is a Flint-owned emulator backend contract. It does not establish Opal server behavior or Android enforcement. No bypass grants, billing, rewards, push delivery, or device-management powers are implemented here.

## Evidence and policy boundary

Opal 4.14.0's Android DTOs recover `ruleType` (`session`, `recurring_schedule`), `id`, optional `name`, `targetGroupId`, `scope`, `config`, `createdAt`, and `updatedAt`. Scope is `group` or `member` with `userId`; block scope is `all_apps` or `distracting_apps`. Recurrence uses local times and ISO weekdays; sessions use instants. Optional `unblocks.allowed` has a distinct unspecified state. Its Android managed-rule serializer rejects writes. See `../docs/research/opal-4.14.0-connected-contracts.md` and decompiled `q00/b.java`, `r00/f.java`, `r00/i.java`, `r00/v.java`, `mn/k.java:1363`.

The following are explicit **Flint policies**, not recovered Opal server rules:

- Only a group's owner authors rules. Group scope applies to role `member`, not its owner. Member scope requires an existing non-owner member of that same group.
- Rules require a name, enabled flag, explicit IANA timezone, and explicit unblocks policy. The timezone controls a future recurrence evaluator; session times remain absolute UTC instants.
- A distracting-app rule pins its own package/domain selection. It does not borrow the member's mutable personal selections. All-app scope still requires the Android integrator to preserve system/emergency surfaces.
- Membership is voluntary. Leaving removes authority from the next complete snapshot. Joining again creates a new opaque `membershipId`; saved member-scoped rules for that user may apply again, but permits from an old membership must never apply.
- No grant route exists. `unblocks.allowed` is stored policy for a future server-authorized bypass milestone; it currently grants no access.
- Rule authoring uses optimistic concurrency. POST creates a new rule on every successful request and must not be automatically retried after an ambiguous network failure; refresh the owner listing first.

## Authentication and routes

All routes require the existing Firebase Auth bearer token, with revoked/disabled/deleted account checks. Direct Firestore clients remain denied. Existing account/group/invite routes retain their contracts.

| Route | Body | Result |
|---|---|---|
| `GET /v1/groups/:groupId/managed-rules` | none | `{revision,rules,tombstones}`; owner only |
| `GET /v1/groups/:groupId/managed-rules/:ruleId` | none | `{revision,rule}`; owner only |
| `POST /v1/groups/:groupId/managed-rules` | `{rule}` | 201 `{revision,rule}`; owner only |
| `PUT /v1/groups/:groupId/managed-rules/:ruleId` | `{expectedRevision,rule}` | `{revision,rule}`; owner only |
| `DELETE /v1/groups/:groupId/managed-rules/:ruleId` | `{expectedRevision}` | `{revision,tombstone}`; owner only |
| `GET /v1/me/managed-state` | none | complete authenticated account snapshot below |

Unsupported fields are rejected recursively. `expectedRevision` must equal the current rule revision, not the global snapshot revision. Stale mutations return 409 `revision_conflict`; missing records 404 `not_found`; foreign/member authoring 403 `forbidden`; validation 422 `invalid_payload`; capacity 409 `managed_rule_limit`. A repeat DELETE with the original expected revision returns its retained tombstone without another mutation. There is no unversioned update/delete route.

## Rule input

```json
{
  "name": "Study time",
  "ruleType": "recurring_schedule",
  "scope": {"kind": "group"},
  "zoneId": "America/New_York",
  "enabled": true,
  "config": {
    "blockScope": {"type": "distracting_apps", "apps": ["com.example.social"], "domains": ["example.com"]},
    "startTime": "09:00",
    "endTime": "15:00",
    "weekdays": [1, 2, 3, 4, 5],
    "unblocks": {"allowed": false}
  }
}
```

- `name`: 1–80 characters, trimmed, no control characters.
- `scope`: exactly `{kind:"group"}` or `{kind:"member",userId:"current-group-member"}`.
- `zoneId`: validated/canonicalized by the server's IANA timezone implementation; `UTC` is accepted, offsets and abbreviations are rejected.
- `blockScope`: exactly `{type:"all_apps"}` or `{type:"distracting_apps",apps:[string],domains:[string]}`. Up to 20 packages and 20 DNS hostnames, with at least one target. Packages are dotted Android package names. Domains are lowercase DNS names, without schemes, paths, wildcards, ports, IP literals, or trailing dot. Duplicates are rejected; arrays are sorted.
- Recurrence times: exactly `HH:mm`; nonempty unique weekdays 1–7, sorted. Equal start/end is rejected; overnight windows are allowed. Weekdays identify the start day. DST evaluation is outside this backend milestone.
- Session config has `blockScope`, `startTime`, `endTime`, `unblocks` and no weekdays. Times require `YYYY-MM-DDTHH:mm:ss[.SSS]Z`, are canonicalized to milliseconds, must be valid calendar instants, and satisfy `start < end` with duration at most seven days.
- `unblocks`: exactly `{allowed:boolean}`.
- Input rule JSON is at most 2 KiB after canonicalization; HTTP request bodies remain limited to 8 KiB. A group has at most 10 live rules. Expired/disabled sessions still count until deleted.

Stored/output rules add server-owned `id`, `targetGroupId`, `revision`, `createdAt`, `updatedAt`. Clients cannot author those fields. Each successful mutation advances a persistent global authority revision; unrelated groups can therefore cause revision gaps.

## Complete account snapshot

```json
{
  "schemaVersion": 1,
  "complete": true,
  "userId": "authenticated-user-id",
  "revision": 42,
  "serverTime": "2026-10-11T18:00:00.000Z",
  "memberships": [{"groupId":"group-id","membershipId":"opaque-uuid","role":"member"}],
  "rules": [],
  "tombstones": []
}
```

Memberships include the actor's owner/member roles; rules and tombstones include only applicable member authority. Owners inspect authored rules through owner routes. The snapshot cannot be requested for another account. Rules are not filtered by current clock activity: an evaluator must check enabled state and interval. No fake empty bypass/quota fields are returned.

Read membership index, group memberships, rules, and revision in one Firestore transaction. A first managed read may migrate legacy membership rows by assigning opaque IDs and advancing the revision atomically. New group creation, invite acceptance, and leave also advance authority revision. Idempotent invite acceptance preserves membership ID and revision; a new invite after leave generates a new membership ID.

Clients must atomically replace the entire managed snapshot, remove omitted groups/rules, reject older revisions for the same identity, and discard responses from an obsolete identity generation. Sign-out clears published authority. A failed fetch is not an empty snapshot. Never place managed rules in the personal editable rule store.

Deleted rules become tombstones `{id,targetGroupId,revision,deletedAt}`. The server retains the latest 64 per group and never reuses rule IDs. Internal tombstones also preserve target scope for filtering and the deleted revision for idempotent DELETE. Omission from every complete snapshot is authoritative even after tombstone pruning, so a client must not merge snapshots additively. Rule retargeting similarly removes the rule from the prior target's full snapshot without requiring a deletion tombstone.

Snapshots are bounded to the existing 20 groups/account, 10 live rules/group, 64 retained tombstones/group, and 1 MiB encoded response. An Android managed-state client must use that response limit rather than the current connected UI repository's 256 KiB limit. No delta/pagination contract is implied.

## Required verification

Real Auth + Firestore emulator tests cover existing routes, owner/member/foreign authorization, session/recurrence validation, explicit scopes/zones, stale and concurrent revision conflicts, deletion/tombstone replay, member retargeting, full snapshot isolation, legacy migration, leave/rejoin identity, monotonic membership revisions, bounds, direct Firestore denial and stale/revoked authentication. Android enforcement, DST recurrence evaluation, restart behavior in Android, and bypass permits are separate integration gates.
