import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';
import { startApi, createApi } from '../src/server.mjs';
import { MAX_MANAGED_RULES, MAX_TOMBSTONES, MANAGED_RESPONSE_LIMIT } from '../src/managed.mjs';

let api;
let endpoint;
const runId = randomUUID();
let accountNumber = 0;
const membershipIdPattern = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/;
async function account() {
  const response = await fetch('http://127.0.0.1:9099/identitytoolkit.googleapis.com/v1/accounts:signUp?key=flint-local-only', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: `managed-${accountNumber++}-${runId}@example.test`, password: 'Local-managed-fixture-7', returnSecureToken: true }),
  });
  assert.equal(response.status, 200);
  const value = await response.json();
  return { id: value.localId, token: value.idToken };
}
async function request(actor, method, path, body) {
  const response = await fetch(endpoint + path, { method,
    headers: { ...(actor ? { Authorization: `Bearer ${actor.token}` } : {}), ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
  return { status: response.status, body: await response.json() };
}
async function success(actor, method, path, body, status = 200) {
  const result = await request(actor, method, path, body);
  assert.equal(result.status, status, result.body.error?.code);
  return result.body;
}
async function join(owner, member, group) {
  const invite = (await success(owner, 'POST', `/v1/groups/${group.id}/invites`, {}, 201)).invite;
  await success(member, 'POST', `/v1/invites/${invite.code}/accept`, {});
  return invite;
}
async function fixture(extraMembers = 0) {
  const [owner, member, foreign, ...others] = await Promise.all(Array.from({ length: 3 + extraMembers }, account));
  const group = (await success(owner, 'POST', '/v1/groups', { name: 'Managed fixture', kind: 'school' }, 201)).group;
  const invite = await join(owner, member, group);
  for (const other of others) await join(owner, other, group);
  return { owner, member, foreign, others, group, invite, path: `/v1/groups/${group.id}/managed-rules` };
}
const snapshot = actor => success(actor, 'GET', '/v1/me/managed-state');
function session(extra = {}) {
  return { name: 'Study session', ruleType: 'session', scope: { kind: 'group' }, zoneId: 'America/New_York', enabled: true,
    config: { blockScope: { type: 'all_apps' }, startTime: '2026-10-11T17:00:00Z', endTime: '2026-10-11T19:00:00Z', unblocks: { allowed: false } }, ...extra };
}
function recurring(extra = {}) {
  return { name: 'Overnight study', ruleType: 'recurring_schedule', scope: { kind: 'group' }, zoneId: 'America/New_York', enabled: true,
    config: { blockScope: { type: 'distracting_apps', apps: ['com.example.video', 'com.example.social'], domains: ['example.com'] },
      startTime: '22:00', endTime: '06:00', weekdays: [5, 1, 3], unblocks: { allowed: true } }, ...extra };
}
async function create(f, rule = session()) { return (await success(f.owner, 'POST', f.path, { rule }, 201)).rule; }

before(async () => {
  api = await startApi({ port: 0 });
  endpoint = `http://127.0.0.1:${api.server.address().port}`;
});
after(async () => { await api?.close(); });

test('managed snapshots are complete, actor-bound and authenticated without invented bypass state', async () => {
  const actor = await account();
  const state = await snapshot(actor);
  assert.equal(state.schemaVersion, 1);
  assert.equal(state.complete, true);
  assert.equal(state.userId, actor.id);
  assert.ok(Number.isSafeInteger(state.revision));
  assert.ok(Number.isFinite(Date.parse(state.serverTime)));
  assert.deepEqual(state.memberships, []);
  assert.deepEqual(state.rules, []);
  assert.deepEqual(state.tombstones, []);
  assert.equal(Object.hasOwn(state, 'bypasses'), false);
  assert.equal(Object.hasOwn(state, 'selfBypass'), false);
  assert.equal((await request(null, 'GET', '/v1/me/managed-state')).status, 401);
  assert.equal((await request(actor, 'GET', '/v1/me/managed-state?userId=someone-else')).status, 422);
  assert.equal((await request(actor, 'POST', '/v1/me/managed-rule-bypasses', {})).status, 404);
});

test('owner authors canonical sessions and recurring rules; members receive only their authority', async () => {
  const f = await fixture();
  const first = await create(f);
  const second = await create(f, recurring());
  assert.ok(second.revision > first.revision);
  assert.equal(first.targetGroupId, f.group.id);
  assert.match(first.id, membershipIdPattern);
  assert.equal(first.config.startTime, '2026-10-11T17:00:00.000Z');
  assert.deepEqual(second.config.weekdays, [1, 3, 5]);
  assert.deepEqual(second.config.blockScope.apps, ['com.example.social', 'com.example.video']);
  assert.deepEqual((await success(f.owner, 'GET', `${f.path}/${first.id}`)).rule, first);
  const listed = await success(f.owner, 'GET', f.path);
  assert.equal(listed.rules.length, 2);
  const memberState = await snapshot(f.member);
  assert.equal(memberState.rules.length, 2);
  assert.match(memberState.memberships[0].membershipId, membershipIdPattern);
  assert.equal(memberState.revision, second.revision);
  assert.equal((await snapshot(f.owner)).rules.length, 0);
  assert.equal((await snapshot(f.foreign)).rules.length, 0);
  assert.ok(Buffer.byteLength(JSON.stringify(memberState)) < MANAGED_RESPONSE_LIMIT);
  for (const actor of [f.member, f.foreign]) {
    assert.equal((await request(actor, 'GET', f.path)).status, 403);
    assert.equal((await request(actor, 'GET', `${f.path}/${first.id}`)).status, 403);
    assert.equal((await request(actor, 'POST', f.path, { rule: session() })).status, 403);
    assert.equal((await request(actor, 'PUT', `${f.path}/${first.id}`, { expectedRevision: first.revision, rule: session() })).status, 403);
    assert.equal((await request(actor, 'DELETE', `${f.path}/${first.id}`, { expectedRevision: first.revision })).status, 403);
  }
});

test('rule input rejects scope escalation, unknown fields, invalid dates, zones, targets and bounds', async () => {
  const f = await fixture();
  const original = await snapshot(f.member);
  const config = session().config;
  const repeating = recurring().config;
  const cases = [
    session({ id: randomUUID() }), session({ targetGroupId: randomUUID() }), session({ revision: 999 }),
    session({ name: '' }), session({ name: 'x'.repeat(81) }), session({ name: 'bad\nname' }),
    session({ enabled: 'true' }), session({ ruleType: 'unknown' }),
    session({ scope: { kind: 'member', userId: f.foreign.id } }), session({ scope: { kind: 'member', userId: f.owner.id } }),
    session({ scope: { kind: 'group', userId: f.member.id } }), session({ scope: { kind: 'member' } }),
    session({ zoneId: 'Not/A_Zone' }), session({ zoneId: '+05:00' }), session({ zoneId: 'EST' }), session({ zoneId: undefined }),
    session({ config: { ...config, startTime: '2026-02-30T17:00:00Z' } }),
    session({ config: { ...config, endTime: config.startTime } }),
    session({ config: { ...config, startTime: '2026-10-11T17:00:00-04:00' } }),
    session({ config: { ...config, endTime: '2026-10-20T19:00:00Z' } }),
    session({ config: { ...config, weekdays: [1] } }), session({ config: { ...config, unblocks: undefined } }),
    session({ config: { ...config, unblocks: { allowed: false, limit: 1 } } }),
    session({ config: { ...config, blockScope: { type: 'all_apps', apps: [] } } }),
    session({ config: { ...config, blockScope: { type: 'distracting_apps', apps: [], domains: [] } } }),
    session({ config: { ...config, blockScope: { type: 'distracting_apps', apps: ['com.example.app', 'com.example.app'], domains: [] } } }),
    session({ config: { ...config, blockScope: { type: 'distracting_apps', apps: ['invalid'], domains: [] } } }),
    session({ config: { ...config, blockScope: { type: 'distracting_apps', apps: [], domains: ['https://example.com'] } } }),
    session({ config: { ...config, blockScope: { type: 'distracting_apps', apps: [], domains: ['127.0.0.1'] } } }),
    session({ config: { ...config, blockScope: { type: 'distracting_apps', apps: [], domains: ['Example.com'] } } }),
    session({ config: { ...config, blockScope: { type: 'distracting_apps', apps: Array.from({ length: 21 }, (_, i) => `com.example.app${i}`), domains: [] } } }),
    recurring({ config: { ...repeating, weekdays: [] } }), recurring({ config: { ...repeating, weekdays: [0, 8] } }),
    recurring({ config: { ...repeating, weekdays: [1, 1] } }), recurring({ config: { ...repeating, weekdays: [1.5] } }),
    recurring({ config: { ...repeating, startTime: '24:00' } }), recurring({ config: { ...repeating, endTime: repeating.startTime } }),
  ];
  for (const rule of cases) assert.equal((await request(f.owner, 'POST', f.path, { rule })).status, 422);
  assert.equal((await request(f.owner, 'POST', f.path, { rule: session(), ownerId: f.foreign.id })).status, 422);
  const missing = session(); delete missing.config.unblocks;
  assert.equal((await request(f.owner, 'POST', f.path, { rule: missing })).status, 422);
  assert.equal((await snapshot(f.member)).revision, original.revision);
  assert.equal((await success(f.owner, 'GET', f.path)).rules.length, 0);
});

test('optimistic concurrent updates commit once and preserve creation identity', async () => {
  const f = await fixture();
  const rule = await create(f);
  const results = await Promise.all(['First edit', 'Second edit'].map(name => request(f.owner, 'PUT', `${f.path}/${rule.id}`,
    { expectedRevision: rule.revision, rule: session({ name, enabled: false, zoneId: 'UTC' }) })));
  assert.deepEqual(results.map(item => item.status).sort(), [200, 409]);
  assert.equal(results.find(item => item.status === 409).body.error.code, 'revision_conflict');
  const updated = results.find(item => item.status === 200).body.rule;
  assert.equal(updated.id, rule.id);
  assert.equal(updated.createdAt, rule.createdAt);
  assert.ok(updated.revision > rule.revision);
  assert.equal(updated.enabled, false);
  assert.equal(updated.zoneId, 'UTC');
  assert.deepEqual((await snapshot(f.member)).rules, [updated]);
  for (const expectedRevision of [0, -1, 1.5, '1', null]) {
    assert.equal((await request(f.owner, 'PUT', `${f.path}/${rule.id}`, { expectedRevision, rule: session() })).status, 422);
  }
  assert.equal((await request(f.owner, 'DELETE', `${f.path}/${rule.id}`, { expectedRevision: rule.revision })).status, 409);
});

test('delete writes scoped tombstones and same-revision retry does not mutate again', async () => {
  const f = await fixture(1);
  const rule = await create(f, session({ scope: { kind: 'member', userId: f.member.id } }));
  const removed = await success(f.owner, 'DELETE', `${f.path}/${rule.id}`, { expectedRevision: rule.revision });
  assert.ok(removed.revision > rule.revision);
  assert.equal(removed.tombstone.id, rule.id);
  assert.equal(removed.tombstone.targetGroupId, f.group.id);
  assert.deepEqual(Object.keys(removed.tombstone).sort(), ['deletedAt', 'id', 'revision', 'targetGroupId']);
  assert.deepEqual(await success(f.owner, 'DELETE', `${f.path}/${rule.id}`, { expectedRevision: rule.revision }), removed);
  assert.equal((await request(f.owner, 'GET', `${f.path}/${rule.id}`)).status, 404);
  const mine = await snapshot(f.member);
  assert.equal(mine.rules.length, 0);
  assert.deepEqual(mine.tombstones, [removed.tombstone]);
  assert.deepEqual((await snapshot(f.others[0])).tombstones, []);
  assert.equal((await request(f.owner, 'PUT', `${f.path}/${rule.id}`, { expectedRevision: rule.revision, rule: session() })).status, 404);
});

test('retargeting removes rules from previous member snapshots without leaking other member rules', async () => {
  const f = await fixture(1);
  const rule = await create(f);
  assert.equal((await snapshot(f.member)).rules.length, 1);
  assert.equal((await snapshot(f.others[0])).rules.length, 1);
  const targeted = (await success(f.owner, 'PUT', `${f.path}/${rule.id}`, { expectedRevision: rule.revision,
    rule: session({ scope: { kind: 'member', userId: f.member.id } }) })).rule;
  assert.equal((await snapshot(f.others[0])).rules.length, 0);
  assert.equal((await snapshot(f.member)).rules.length, 1);
  const retargeted = (await success(f.owner, 'PUT', `${f.path}/${rule.id}`, { expectedRevision: targeted.revision,
    rule: session({ scope: { kind: 'member', userId: f.others[0].id } }) })).rule;
  const old = await snapshot(f.member);
  assert.equal(old.complete, true);
  assert.equal(old.rules.length, 0);
  assert.equal(old.tombstones.length, 0);
  assert.deepEqual((await snapshot(f.others[0])).rules, [retargeted]);
});

test('leave and rejoin advance revision and regenerate membership identity; retries preserve it', async () => {
  const f = await fixture();
  await create(f);
  const joined = await snapshot(f.member);
  await success(f.member, 'POST', `/v1/invites/${f.invite.code}/accept`, {});
  const replay = await snapshot(f.member);
  assert.equal(replay.revision, joined.revision);
  assert.equal(replay.memberships[0].membershipId, joined.memberships[0].membershipId);
  await success(f.member, 'POST', `/v1/groups/${f.group.id}/leave`, {});
  const left = await snapshot(f.member);
  assert.ok(left.revision > joined.revision);
  assert.deepEqual(left.memberships, []);
  assert.deepEqual(left.rules, []);
  assert.deepEqual(left.tombstones, []);
  assert.equal((await request(f.member, 'POST', `/v1/invites/${f.invite.code}/accept`, {})).status, 409);
  const invitation = await join(f.owner, f.member, f.group);
  const rejoined = await snapshot(f.member);
  assert.ok(rejoined.revision > left.revision);
  assert.notEqual(rejoined.memberships[0].membershipId, joined.memberships[0].membershipId);
  assert.equal(rejoined.rules.length, 1);
  await success(f.member, 'POST', `/v1/invites/${invitation.code}/accept`, {});
  assert.equal((await snapshot(f.member)).memberships[0].membershipId, rejoined.memberships[0].membershipId);
});

test('legacy memberships migrate once atomically, and corrupt indexes never produce an empty success', async () => {
  const f = await fixture();
  const ref = api.db.collection('groups').doc(f.group.id);
  const stored = (await ref.get()).data();
  for (const member of Object.values(stored.members)) delete member.membershipId;
  await ref.set(stored);
  const beforeRevision = (await api.db.collection('managedMetadata').doc('state').get()).data().revision;
  const migrated = await snapshot(f.member);
  assert.ok(migrated.revision > beforeRevision);
  assert.match(migrated.memberships[0].membershipId, membershipIdPattern);
  const repeated = await snapshot(f.member);
  assert.equal(repeated.revision, migrated.revision);
  assert.deepEqual(repeated.memberships, migrated.memberships);
  assert.match((await ref.get()).data().members[f.owner.id].membershipId, membershipIdPattern);
  await api.db.collection('userGroups').doc(f.foreign.id).set({ groupIds: [f.group.id] });
  const broken = await request(f.foreign, 'GET', '/v1/me/managed-state');
  assert.equal(broken.status, 503);
  assert.equal(broken.body.error.code, 'authority_unavailable');
});

test('live rule capacity is enforced and deleted entries release capacity', async () => {
  const f = await fixture();
  const created = [];
  for (let i = 0; i < MAX_MANAGED_RULES; i += 1) created.push(await create(f, session({ name: `Rule ${i}` })));
  const excess = await request(f.owner, 'POST', f.path, { rule: session() });
  assert.equal(excess.status, 409);
  assert.equal(excess.body.error.code, 'managed_rule_limit');
  await success(f.owner, 'DELETE', `${f.path}/${created[0].id}`, { expectedRevision: created[0].revision });
  const newRule = await create(f);
  assert.notEqual(newRule.id, created[0].id);
  const state = await snapshot(f.member);
  assert.equal(state.rules.length, MAX_MANAGED_RULES);
  assert.equal(state.tombstones.length, 1);
});

test('bounded tombstone retention cannot resurrect deleted rules in complete snapshots', async () => {
  const f = await fixture();
  const rule = await create(f);
  const ref = api.db.collection('managedGroups').doc(f.group.id);
  const oldestId = randomUUID();
  const retained = Array.from({ length: MAX_TOMBSTONES }, (_, i) => ({ id: i === 0 ? oldestId : randomUUID(),
    targetGroupId: f.group.id, revision: rule.revision, deletedAt: rule.createdAt, scope: { kind: 'group' }, deletedRevision: 1 }));
  await ref.update({ tombstones: retained });
  await success(f.owner, 'DELETE', `${f.path}/${rule.id}`, { expectedRevision: rule.revision });
  const state = await snapshot(f.member);
  assert.equal(state.complete, true);
  assert.deepEqual(state.rules, []);
  assert.equal(state.tombstones.length, MAX_TOMBSTONES);
  assert.equal(state.tombstones.some(item => item.id === oldestId), false);
  assert.equal(state.tombstones.some(item => item.id === rule.id), true);
});

test('managed data is denied to direct Firestore clients and revoked sessions', async () => {
  const f = await fixture();
  const rule = await create(f);
  const url = `http://127.0.0.1:8085/v1/projects/demo-flint/databases/(default)/documents/managedGroups/${f.group.id}`;
  assert.equal((await fetch(url, { headers: { Authorization: `Bearer ${f.owner.token}` } })).status, 403);
  assert.equal((await fetch(url, { method: 'PATCH', headers: { Authorization: `Bearer ${f.owner.token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ fields: { revision: { integerValue: '999' } } }) })).status, 403);
  await delay(1100);
  await api.auth.revokeRefreshTokens(f.member.id);
  assert.equal((await request(f.member, 'GET', '/v1/me/managed-state')).status, 401);
  await api.auth.updateUser(f.owner.id, { disabled: true });
  assert.equal((await request(f.owner, 'DELETE', `${f.path}/${rule.id}`, { expectedRevision: rule.revision })).status, 401);
});

test('API process recreation retains managed revisions, rules and membership identities', async () => {
  const f = await fixture();
  await create(f, recurring());
  const beforeState = await snapshot(f.member);
  const second = createApi({ auth: api.auth, db: api.db });
  await new Promise(resolve => second.listen(0, '127.0.0.1', resolve));
  try {
    const response = await fetch(`http://127.0.0.1:${second.address().port}/v1/me/managed-state`, { headers: { Authorization: `Bearer ${f.member.token}` } });
    assert.equal(response.status, 200);
    const afterState = await response.json();
    assert.equal(afterState.revision, beforeState.revision);
    assert.deepEqual(afterState.rules, beforeState.rules);
    assert.deepEqual(afterState.memberships, beforeState.memberships);
  } finally { second.closeAllConnections(); await new Promise(resolve => second.close(resolve)); }
});
