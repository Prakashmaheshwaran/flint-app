import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { fail, objectBody } from './errors.mjs';
import { createManagedService, authorityRef, advanceRevision } from './managed.mjs';

export { ApiError, objectBody } from './errors.mjs';

const MAX_GROUPS = 20;
const MAX_MEMBERS = 100;
const MAX_PENDING_INVITES = 20;
const HOUR = 3_600_000;
const TOKEN_PATTERN = /^[A-Za-z0-9_-]{43}$/;
const ID_PATTERN = /^[a-f0-9-]{36}$/;

const iso = (value) => new Date(value).toISOString();
const hashed = (code) => createHash('sha256').update(code).digest('hex');

function textField(value, name, max) {
  if (typeof value !== 'string' || value.trim().length < 1 || value.trim().length > max || /[\u0000-\u001f\u007f]/u.test(value)) {
    fail(422, 'invalid_payload', `${name} must contain 1–${max} characters without control characters.`);
  }
  return value.trim();
}
function groupId(value) {
  if (!ID_PATTERN.test(value)) fail(404, 'not_found', 'Group not found.');
  return value;
}
function inviteHash(code) {
  if (!TOKEN_PATTERN.test(code)) fail(404, 'not_found', 'Invitation not found.');
  return hashed(code);
}
function membership(group, uid) {
  if (!group) fail(404, 'not_found', 'Group not found.');
  const item = group.members[uid];
  if (!Object.hasOwn(group.members, uid) || !item) fail(403, 'forbidden', 'Group membership is required.');
  return item;
}
function summary(id, group, uid) {
  const member = membership(group, uid);
  return { id, name: group.name, kind: group.kind, ownerId: group.ownerId,
    role: member.role, memberCount: group.memberIds.length, createdAt: group.createdAt, updatedAt: group.updatedAt };
}
function defaultProfile(actor, now) {
  return { id: actor.uid, email: actor.email ?? null,
    displayName: typeof actor.name === 'string' && actor.name.trim() ? actor.name.trim().slice(0, 80) : 'Flint member',
    createdAt: iso(now), updatedAt: iso(now) };
}
function owner(group, uid) {
  membership(group, uid);
  if (group.ownerId !== uid) fail(403, 'forbidden', 'Only the group owner can perform this action.');
}

export function createService(db, { now = () => Date.now() } = {}) {
  const groups = db.collection('groups');
  const users = db.collection('users');
  const invites = db.collection('invites');
  const indexes = db.collection('userGroups');
  const metadata = authorityRef(db);
  const profileFor = async actor => db.runTransaction(async tx => {
    const ref = users.doc(actor.uid);
    const snapshot = await tx.get(ref);
    if (snapshot.exists) return { ...snapshot.data(), email: actor.email ?? null };
    const profile = defaultProfile(actor, now());
    tx.create(ref, profile);
    return profile;
  });

  return {
    ...createManagedService(db, { now, groupId, membership, owner }),
    async me(actor) { return { user: await profileFor(actor) }; },
    async updateMe(actor, body) {
      objectBody(body, ['displayName']);
      const displayName = textField(body.displayName, 'displayName', 80);
      const user = await db.runTransaction(async tx => {
        const ref = users.doc(actor.uid);
        const snapshot = await tx.get(ref);
        const timestamp = now();
        const profile = snapshot.exists ? snapshot.data() : defaultProfile(actor, timestamp);
        const updated = { ...profile, email: actor.email ?? null, displayName, updatedAt: iso(timestamp) };
        tx.set(ref, updated);
        return updated;
      });
      return { user };
    },
    async listGroups(actor) {
      const snapshot = await groups.where('memberIds', 'array-contains', actor.uid).limit(MAX_GROUPS + 1).get();
      return { groups: snapshot.docs.map(doc => summary(doc.id, doc.data(), actor.uid)).sort((a, b) => a.createdAt.localeCompare(b.createdAt)) };
    },
    async createGroup(actor, body) {
      objectBody(body, ['name', 'kind']);
      const name = textField(body.name, 'name', 80);
      if (!['family', 'school'].includes(body.kind)) fail(422, 'invalid_payload', 'kind must be family or school.');
      const id = randomUUID();
      return db.runTransaction(async tx => {
        const indexRef = indexes.doc(actor.uid);
        const [index, meta] = await tx.getAll(indexRef, metadata);
        const ids = index.data()?.groupIds ?? [];
        if (ids.length >= MAX_GROUPS) fail(409, 'group_limit', 'The account has reached its group limit.');
        const time = iso(now());
        const group = { name, kind: body.kind, ownerId: actor.uid, createdAt: time, updatedAt: time,
          memberIds: [actor.uid], members: { [actor.uid]: { role: 'owner', joinedAt: time, membershipId: randomUUID() } } };
        advanceRevision(tx, metadata, meta, Date.parse(time));
        tx.create(groups.doc(id), group);
        tx.set(indexRef, { groupIds: [...ids, id] });
        return { group: summary(id, group, actor.uid) };
      });
    },
    async getGroup(actor, id) {
      const doc = await groups.doc(groupId(id)).get();
      return { group: summary(id, doc.data(), actor.uid) };
    },
    async listMembers(actor, id) {
      // A transaction keeps membership authorization and the returned member list consistent.
      return db.runTransaction(async tx => {
        const snapshot = await tx.get(groups.doc(groupId(id)));
        const group = snapshot.data();
        membership(group, actor.uid);
        const profiles = await tx.getAll(...group.memberIds.map(uid => users.doc(uid)));
        const names = new Map(profiles.map(doc => [doc.id, doc.data()?.displayName ?? 'Flint member']));
        return { members: group.memberIds.map(userId => ({ userId, displayName: names.get(userId), ...group.members[userId] })) };
      });
    },
    async createInvite(actor, id, body) {
      objectBody(body, ['expiresInHours']);
      const hours = body.expiresInHours ?? 24;
      if (!Number.isInteger(hours) || hours < 1 || hours > 168) fail(422, 'invalid_payload', 'expiresInHours must be an integer from 1 to 168.');
      const code = randomBytes(32).toString('base64url');
      const inviteId = hashed(code);
      const ref = groups.doc(groupId(id));
      return db.runTransaction(async tx => {
        const snapshot = await tx.get(ref);
        const group = snapshot.data();
        owner(group, actor.uid);
        const timestamp = now();
        const pending = Object.fromEntries(Object.entries(group.pendingInvites ?? {}).filter(([, expiry]) => expiry > timestamp));
        if (Object.keys(pending).length >= MAX_PENDING_INVITES) fail(409, 'invite_limit', 'The group has reached its pending invitation limit.');
        const expiresAt = iso(timestamp + hours * HOUR);
        pending[inviteId] = timestamp + hours * HOUR;
        tx.create(invites.doc(inviteId), { groupId: id, createdBy: actor.uid, createdAt: iso(timestamp), expiresAt,
          role: 'member', consumedBy: null, consumedAt: null });
        tx.update(ref, { pendingInvites: pending });
        return { invite: { code, groupId: id, expiresAt, role: 'member' } };
      });
    },
    async previewInvite(actor, code) {
      const ref = invites.doc(inviteHash(code));
      return db.runTransaction(async tx => {
        const snapshot = await tx.get(ref);
        const invite = snapshot.data();
        if (!invite) fail(404, 'not_found', 'Invitation not found.');
        if (invite.consumedBy && invite.consumedBy !== actor.uid) fail(409, 'invite_used', 'Invitation has already been used.');
        if (!invite.consumedBy && Date.parse(invite.expiresAt) <= now()) fail(410, 'invite_expired', 'Invitation has expired.');
        const groupSnapshot = await tx.get(groups.doc(invite.groupId));
        const group = groupSnapshot.data();
        if (!group) fail(404, 'not_found', 'Group not found.');
        if (invite.consumedBy && !group.memberIds.includes(actor.uid)) fail(409, 'invite_used', 'Invitation has already been used.');
        return { invite: { groupId: invite.groupId, groupName: group.name, kind: group.kind, role: invite.role, expiresAt: invite.expiresAt } };
      });
    },
    async acceptInvite(actor, code, body) {
      objectBody(body, []);
      const ref = invites.doc(inviteHash(code));
      return db.runTransaction(async tx => {
        const snapshot = await tx.get(ref);
        const invite = snapshot.data();
        if (!invite) fail(404, 'not_found', 'Invitation not found.');
        const groupRef = groups.doc(invite.groupId);
        const indexRef = indexes.doc(actor.uid);
        const [groupSnapshot, indexSnapshot, meta] = await tx.getAll(groupRef, indexRef, metadata);
        const group = groupSnapshot.data();
        if (!group) fail(404, 'not_found', 'Group not found.');
        if (invite.consumedBy) {
          if (invite.consumedBy === actor.uid && group.memberIds.includes(actor.uid)) return { group: summary(invite.groupId, group, actor.uid) };
          fail(409, 'invite_used', 'Invitation has already been used.');
        }
        const timestamp = now();
        if (Date.parse(invite.expiresAt) <= timestamp) fail(410, 'invite_expired', 'Invitation has expired.');
        if (group.memberIds.includes(actor.uid)) fail(409, 'already_member', 'The account is already a member.');
        const ids = indexSnapshot.data()?.groupIds ?? [];
        if (ids.length >= MAX_GROUPS) fail(409, 'group_limit', 'The account has reached its group limit.');
        if (group.memberIds.length >= MAX_MEMBERS) fail(409, 'member_limit', 'The group has reached its member limit.');
        const time = iso(timestamp);
        const pending = { ...(group.pendingInvites ?? {}) };
        delete pending[ref.id];
        const updated = { ...group, updatedAt: time, memberIds: [...group.memberIds, actor.uid],
          members: { ...group.members, [actor.uid]: { role: 'member', joinedAt: time, membershipId: randomUUID() } }, pendingInvites: pending };
        advanceRevision(tx, metadata, meta, timestamp);
        tx.set(groupRef, updated);
        tx.set(indexRef, { groupIds: [...ids, invite.groupId] });
        tx.update(ref, { consumedBy: actor.uid, consumedAt: time });
        return { group: summary(invite.groupId, updated, actor.uid) };
      });
    },
    async leaveGroup(actor, id, body) {
      objectBody(body, []);
      const ref = groups.doc(groupId(id));
      return db.runTransaction(async tx => {
        const indexRef = indexes.doc(actor.uid);
        const [snapshot, index, meta] = await tx.getAll(ref, indexRef, metadata);
        const group = snapshot.data();
        membership(group, actor.uid);
        if (group.ownerId === actor.uid) fail(409, 'owner_cannot_leave', 'The owner cannot leave this group.');
        const members = { ...group.members };
        delete members[actor.uid];
        advanceRevision(tx, metadata, meta, now());
        tx.update(ref, { members, memberIds: group.memberIds.filter(uid => uid !== actor.uid), updatedAt: iso(now()) });
        tx.set(indexRef, { groupIds: (index.data()?.groupIds ?? []).filter(groupId => groupId !== id) });
        return { left: true };
      });
    },
  };
}
