import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';
import { startApi } from '../src/server.mjs';
import { assertEmulatorConfig, LOCAL_ENV, localEnvironment } from '../src/config.mjs';

let api;
let endpoint;
let owner;
let member;
let foreign;
let group;
let invitation;
const runId = randomUUID();

async function account(label) {
  const response = await fetch('http://127.0.0.1:9099/identitytoolkit.googleapis.com/v1/accounts:signUp?key=flint-local-only', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: `${label}-${runId}@example.test`, password: 'Only-for-local-emulator-7', returnSecureToken: true }),
  });
  assert.equal(response.status, 200);
  const value = await response.json();
  return { id: value.localId, token: value.idToken };
}
async function request(actor, method, path, body, extra = {}) {
  const response = await fetch(`${endpoint}${path}`, { method,
    headers: { ...(actor ? { Authorization: `Bearer ${actor.token}` } : {}),
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}), ...extra },
    ...(body !== undefined ? { body: JSON.stringify(body) } : {}) });
  return { status: response.status, body: await response.json() };
}
async function success(actor, method, path, body, status = 200) {
  const result = await request(actor, method, path, body);
  assert.equal(result.status, status, result.body.error?.code);
  return result.body;
}
function mutatedToken(token, values) {
  const [header, payload, signature] = token.split('.');
  return `${header}.${Buffer.from(JSON.stringify({ ...JSON.parse(Buffer.from(payload, 'base64url')), ...values })).toString('base64url')}.${signature}`;
}

before(async () => {
  assertEmulatorConfig();
  api = await startApi({ port: 0 });
  endpoint = `http://127.0.0.1:${api.server.address().port}`;
  [owner, member, foreign] = await Promise.all(['owner', 'member', 'foreign'].map(account));
});
after(async () => { await api?.close(); });

test('configuration rejects cloud projects, non-loopback emulators, credentials, and production mode', () => {
  const base = { ...LOCAL_ENV };
  assert.doesNotThrow(() => assertEmulatorConfig(base));
  for (const bad of [{ GCLOUD_PROJECT: 'live-project' }, { FIRESTORE_EMULATOR_HOST: 'db.example.com:8080' },
    { FIREBASE_AUTH_EMULATOR_HOST: '0.0.0.0:9099' }, { GOOGLE_APPLICATION_CREDENTIALS: '/tmp/key.json' }, { NODE_ENV: 'production' }]) {
    assert.throws(() => assertEmulatorConfig({ ...base, ...bad }));
  }
  assert.throws(() => localEnvironment({ GCLOUD_PROJECT: 'real-project' }));
});

test('health declares emulator-only mode, and protected requests require valid sessions', async () => {
  assert.deepEqual((await request(null, 'GET', '/health')).body, { status: 'ok', mode: 'emulator-only', projectId: 'demo-flint' });
  assert.equal((await request(null, 'GET', '/v1/me')).status, 401);
  assert.equal((await request({ token: 'invalid' }, 'GET', '/v1/me')).status, 401);
  assert.equal((await request(owner, 'GET', '/v1/me', undefined, { Origin: 'https://untrusted.example' })).status, 403);
});

test('profile writes are scoped to the signed-in user and enforce fields and size', async () => {
  const initial = await success(owner, 'GET', '/v1/me');
  assert.equal(initial.user.id, owner.id);
  const updated = await success(owner, 'PATCH', '/v1/me', { displayName: 'Family owner' });
  assert.equal(updated.user.displayName, 'Family owner');
  assert.equal(updated.user.id, owner.id);
  assert.equal((await request(owner, 'PATCH', '/v1/me', { displayName: 'Hijacked', id: foreign.id })).status, 422);
  assert.equal((await request(owner, 'PATCH', '/v1/me', { displayName: '' })).status, 422);
  assert.equal((await request(owner, 'PATCH', '/v1/me', { displayName: 'x'.repeat(81) })).status, 422);
  assert.equal((await request(owner, 'PATCH', '/v1/me', { displayName: 'x'.repeat(9000) })).status, 413);
  assert.equal((await request(owner, 'PATCH', '/v1/me', ['invalid'])).status, 422);
  assert.equal((await success(foreign, 'GET', '/v1/me')).user.displayName, 'Flint member');
  await success(member, 'PATCH', '/v1/me', { displayName: 'Invited member' });
});

test('group creation assigns owner authority and rejects foreign group access', async () => {
  assert.equal((await request(owner, 'POST', '/v1/groups', { name: 'Home', kind: 'business' })).status, 422);
  assert.equal((await request(owner, 'POST', '/v1/groups', { name: 'Home', kind: 'family', ownerId: foreign.id })).status, 422);
  group = (await success(owner, 'POST', '/v1/groups', { name: 'Home', kind: 'family' }, 201)).group;
  assert.equal(group.ownerId, owner.id);
  assert.equal(group.role, 'owner');
  assert.equal(group.memberCount, 1);
  assert.equal((await success(owner, 'GET', '/v1/groups')).groups.length, 1);
  assert.equal((await success(foreign, 'GET', '/v1/groups')).groups.length, 0);
  for (const path of [`/v1/groups/${group.id}`, `/v1/groups/${group.id}/members`]) {
    assert.equal((await request(foreign, 'GET', path)).status, 403);
  }
  assert.equal((await request(foreign, 'POST', `/v1/groups/${group.id}/invites`, {})).status, 403);
  assert.equal((await request(owner, 'POST', `/v1/groups/${group.id}/leave`, {})).status, 409);
});

test('owner creates a hashed one-use invite; authenticated preview and acceptance are explicit', async () => {
  assert.equal((await request(owner, 'POST', `/v1/groups/${group.id}/invites`, { expiresInHours: 169 })).status, 422);
  assert.equal((await request(owner, 'POST', `/v1/groups/${group.id}/invites`, { role: 'owner' })).status, 422);
  invitation = (await success(owner, 'POST', `/v1/groups/${group.id}/invites`, {}, 201)).invite;
  assert.match(invitation.code, /^[A-Za-z0-9_-]{43}$/);
  const hash = createHash('sha256').update(invitation.code).digest('hex');
  const stored = await api.db.collection('invites').doc(hash).get();
  assert.equal(stored.exists, true);
  assert.equal(JSON.stringify(stored.data()).includes(invitation.code), false);
  assert.equal((await api.db.collection('invites').doc(invitation.code).get()).exists, false);
  assert.equal((await request(null, 'GET', `/v1/invites/${invitation.code}`)).status, 401);
  const preview = await success(member, 'GET', `/v1/invites/${invitation.code}`);
  assert.equal(preview.invite.groupName, 'Home');
  assert.equal((await success(member, 'GET', '/v1/groups')).groups.length, 0);
  const accepted = await success(member, 'POST', `/v1/invites/${invitation.code}/accept`, {});
  assert.equal(accepted.group.role, 'member');
  assert.equal(accepted.group.memberCount, 2);
  const retry = await success(member, 'POST', `/v1/invites/${invitation.code}/accept`, {});
  assert.deepEqual(retry, accepted);
  assert.equal((await request(foreign, 'POST', `/v1/invites/${invitation.code}/accept`, {})).status, 409);
  assert.equal((await request(foreign, 'GET', `/v1/invites/${invitation.code}`)).status, 409);
});

test('members can read membership but cannot issue invitations or leak account emails', async () => {
  const members = (await success(member, 'GET', `/v1/groups/${group.id}/members`)).members;
  assert.equal(members.length, 2);
  assert.equal(members.find(item => item.userId === member.id).displayName, 'Invited member');
  assert.ok(members.every(item => !Object.hasOwn(item, 'email')));
  assert.equal((await request(member, 'POST', `/v1/groups/${group.id}/invites`, {})).status, 403);
});

test('concurrent invite consumers commit exactly one membership', async () => {
  const [first, second] = await Promise.all(['race-one', 'race-two'].map(account));
  const invite = (await success(owner, 'POST', `/v1/groups/${group.id}/invites`, {}, 201)).invite;
  const results = await Promise.all([first, second].map(actor => request(actor, 'POST', `/v1/invites/${invite.code}/accept`, {})));
  assert.deepEqual(results.map(result => result.status).sort(), [200, 409]);
  const members = (await success(owner, 'GET', `/v1/groups/${group.id}/members`)).members;
  assert.equal(members.filter(item => [first.id, second.id].includes(item.userId)).length, 1);
});

test('expired invites fail without consuming or joining; invalid payloads cannot escalate', async () => {
  const invite = (await success(owner, 'POST', `/v1/groups/${group.id}/invites`, {}, 201)).invite;
  const hash = createHash('sha256').update(invite.code).digest('hex');
  await api.db.collection('invites').doc(hash).update({ expiresAt: new Date(Date.now() - 1000).toISOString() });
  assert.equal((await request(foreign, 'GET', `/v1/invites/${invite.code}`)).status, 410);
  assert.equal((await request(foreign, 'POST', `/v1/invites/${invite.code}/accept`, {})).status, 410);
  assert.equal((await api.db.collection('invites').doc(hash).get()).data().consumedBy, null);
  assert.equal((await request(foreign, 'POST', `/v1/invites/${invite.code}/accept`, { role: 'owner' })).status, 422);
  assert.equal((await success(foreign, 'GET', '/v1/groups')).groups.length, 0);
});

test('leaving removes member authority and a consumed code cannot restore access', async () => {
  assert.deepEqual(await success(member, 'POST', `/v1/groups/${group.id}/leave`, {}), { left: true });
  assert.equal((await success(member, 'GET', '/v1/groups')).groups.length, 0);
  assert.equal((await request(member, 'GET', `/v1/groups/${group.id}/members`)).status, 403);
  assert.equal((await request(member, 'POST', `/v1/invites/${invitation.code}/accept`, {})).status, 409);
  assert.equal((await request(member, 'GET', `/v1/invites/${invitation.code}`)).status, 409);
});

test('Firestore rules reject direct authenticated reads and writes', async () => {
  const url = `http://127.0.0.1:8085/v1/projects/demo-flint/databases/(default)/documents/groups/${group.id}`;
  assert.equal((await fetch(url, { headers: { Authorization: `Bearer ${owner.token}` } })).status, 403);
  assert.equal((await fetch(url, { method: 'PATCH', headers: { Authorization: `Bearer ${owner.token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ fields: { ownerId: { stringValue: foreign.id } } }) })).status, 403);
});

test('expired, disabled, deleted, and revoked sessions are rejected before authorization', async () => {
  const expired = { token: mutatedToken(foreign.token, { exp: 1 }) };
  assert.equal((await request(expired, 'GET', '/v1/me')).status, 401);
  const disabled = await account('disabled');
  await api.auth.updateUser(disabled.id, { disabled: true });
  assert.equal((await request(disabled, 'GET', '/v1/me')).status, 401);
  const removed = await account('removed');
  await api.auth.deleteUser(removed.id);
  assert.equal((await request(removed, 'GET', '/v1/me')).status, 401);
  const revoked = await account('revoked');
  await delay(1100); // Firebase auth_time and valid-since use whole seconds.
  await api.auth.revokeRefreshTokens(revoked.id);
  assert.equal((await request(revoked, 'GET', '/v1/me')).status, 401);
});

test('malformed JSON, unsupported query parameters, and unbounded requests are rejected', async () => {
  const response = await fetch(`${endpoint}/v1/me`, { method: 'PATCH', headers: {
    Authorization: `Bearer ${foreign.token}`, 'Content-Type': 'application/json' }, body: '{' });
  assert.equal(response.status, 400);
  assert.equal((await request(foreign, 'GET', '/v1/me?userId=other')).status, 422);
  let last;
  for (let i = 0; i < 121; i += 1) last = await request(foreign, 'GET', '/v1/me');
  assert.equal(last.status, 429);
});
