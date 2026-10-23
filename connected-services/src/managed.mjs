import { randomUUID } from 'node:crypto';
import { fail, objectBody } from './errors.mjs';

export const MAX_MANAGED_RULES = 10;
export const MAX_TOMBSTONES = 64;
export const MANAGED_RESPONSE_LIMIT = 1_048_576;
const MAX_RULE_BYTES = 2048;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const iso = value => new Date(value).toISOString();
const invalid = message => fail(422, 'invalid_payload', message);

export function authorityRef(db) { return db.collection('managedMetadata').doc('state'); }

export function revisionOf(snapshot) {
  const revision = snapshot.exists ? snapshot.data().revision : 0;
  if (!Number.isSafeInteger(revision) || revision < 0 || revision === Number.MAX_SAFE_INTEGER) {
    fail(503, 'authority_unavailable', 'The authority revision is unavailable.');
  }
  return revision;
}

export function advanceRevision(tx, ref, snapshot, now) {
  const revision = revisionOf(snapshot) + 1;
  tx.set(ref, { revision, updatedAt: iso(now) });
  return revision;
}

/** Legacy membership migration is committed with the managed snapshot/mutation transaction. */
function normalizedMemberships(group) {
  let changed = false;
  const members = Object.fromEntries(Object.entries(group.members).map(([uid, member]) => {
    if (typeof member.membershipId === 'string' && UUID.test(member.membershipId)) return [uid, member];
    changed = true;
    return [uid, { ...member, membershipId: randomUUID() }];
  }));
  return { group: changed ? { ...group, members } : group, changed };
}

function id(value) {
  if (typeof value !== 'string' || !UUID.test(value)) fail(404, 'not_found', 'Managed rule not found.');
  return value;
}
function expected(value) {
  if (!Number.isSafeInteger(value) || value < 1) invalid('expectedRevision must be a positive integer.');
  return value;
}
function name(value) {
  if (typeof value !== 'string' || value.trim().length < 1 || value.trim().length > 80 || /[\u0000-\u001f\u007f]/u.test(value)) {
    invalid('name must contain 1–80 characters without control characters.');
  }
  return value.trim();
}
function zone(value) {
  if (typeof value !== 'string' || value.length > 64 || !(value === 'UTC' || /^[A-Za-z_]+(?:\/[A-Za-z0-9_+\-]+)+$/.test(value))) {
    invalid('zoneId must be an IANA timezone or UTC.');
  }
  try { return new Intl.DateTimeFormat('en-US', { timeZone: value }).resolvedOptions().timeZone; }
  catch { invalid('zoneId must be a recognized IANA timezone.'); }
}
function targetArray(value, label, isValid) {
  if (!Array.isArray(value) || value.length > 20 || value.some(item => typeof item !== 'string' || !isValid(item)) || new Set(value).size !== value.length) {
    invalid(`${label} must contain at most 20 unique valid targets.`);
  }
  return [...value].sort();
}
function packageName(value) {
  return value.length <= 255 && /^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$/.test(value);
}
function hostname(value) {
  return value.length <= 253 && value === value.toLowerCase() && value.includes('.') &&
    !/^[0-9.]+$/.test(value) && value.split('.').every(part => /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(part));
}
function blockScope(value) {
  objectBody(value, ['type', 'apps', 'domains']);
  if (value.type === 'all_apps') {
    objectBody(value, ['type']);
    return { type: 'all_apps' };
  }
  if (value.type !== 'distracting_apps') invalid('blockScope.type must be all_apps or distracting_apps.');
  const apps = targetArray(value.apps, 'apps', packageName);
  const domains = targetArray(value.domains, 'domains', hostname);
  if (!apps.length && !domains.length) invalid('A distracting-app rule must specify at least one target.');
  return { type: 'distracting_apps', apps, domains };
}
function instant(value) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3})?Z$/.test(value)) {
    invalid('Session times must be UTC instants with seconds and optional milliseconds.');
  }
  const ms = Date.parse(value);
  const canonical = value.includes('.') ? value : value.replace('Z', '.000Z');
  if (!Number.isFinite(ms) || iso(ms) !== canonical) invalid('Session times must be valid calendar instants.');
  return canonical;
}
function localTime(value) {
  if (typeof value !== 'string' || !/^(?:[01]\d|2[0-3]):[0-5]\d$/.test(value)) invalid('Recurring times must use HH:mm.');
  return value;
}
function scope(value, group) {
  objectBody(value, ['kind', 'userId']);
  if (value.kind === 'group') { objectBody(value, ['kind']); return { kind: 'group' }; }
  if (value.kind !== 'member' || typeof value.userId !== 'string' || !Object.hasOwn(group.members, value.userId) ||
      group.members[value.userId].role !== 'member' || group.ownerId === value.userId) {
    invalid('Member scope must identify a current non-owner member of this group.');
  }
  return { kind: 'member', userId: value.userId };
}
function ruleInput(value, group) {
  objectBody(value, ['name', 'ruleType', 'scope', 'zoneId', 'enabled', 'config']);
  if (!['session', 'recurring_schedule'].includes(value.ruleType)) invalid('ruleType must be session or recurring_schedule.');
  if (typeof value.enabled !== 'boolean') invalid('enabled must be a boolean.');
  const config = objectBody(value.config, value.ruleType === 'session'
    ? ['blockScope', 'startTime', 'endTime', 'unblocks']
    : ['blockScope', 'startTime', 'endTime', 'weekdays', 'unblocks']);
  objectBody(config.unblocks, ['allowed']);
  if (typeof config.unblocks.allowed !== 'boolean') invalid('unblocks.allowed must be a boolean.');
  const parsed = { blockScope: blockScope(config.blockScope), unblocks: { allowed: config.unblocks.allowed } };
  if (value.ruleType === 'session') {
    parsed.startTime = instant(config.startTime);
    parsed.endTime = instant(config.endTime);
    const duration = Date.parse(parsed.endTime) - Date.parse(parsed.startTime);
    if (duration <= 0 || duration > 7 * 86_400_000) invalid('A session must end after its start and last at most seven days.');
  } else {
    parsed.startTime = localTime(config.startTime);
    parsed.endTime = localTime(config.endTime);
    if (parsed.startTime === parsed.endTime) invalid('A recurring rule must have distinct start and end times.');
    if (!Array.isArray(config.weekdays) || config.weekdays.length < 1 || config.weekdays.length > 7 ||
        config.weekdays.some(day => !Number.isInteger(day) || day < 1 || day > 7) || new Set(config.weekdays).size !== config.weekdays.length) {
      invalid('weekdays must contain unique ISO day numbers from 1 to 7.');
    }
    parsed.weekdays = [...config.weekdays].sort((a, b) => a - b);
  }
  const rule = { name: name(value.name), ruleType: value.ruleType, scope: scope(value.scope, group),
    zoneId: zone(value.zoneId), enabled: value.enabled, config: parsed };
  if (Buffer.byteLength(JSON.stringify(rule)) > MAX_RULE_BYTES) invalid('The managed rule exceeds 2 KiB.');
  return rule;
}
function applicable(rule, group, uid) {
  if (!Object.hasOwn(group.members, uid) || group.members[uid].role !== 'member') return false;
  return rule.scope.kind === 'group' || rule.scope.kind === 'member' && rule.scope.userId === uid;
}
const publicTombstone = ({ id, targetGroupId, revision, deletedAt }) => ({ id, targetGroupId, revision, deletedAt });
const byId = (a, b) => a.id.localeCompare(b.id);

export function createManagedService(db, { now, groupId, membership, owner }) {
  const groups = db.collection('groups');
  const states = db.collection('managedGroups');
  const metadata = authorityRef(db);

  async function ownerRead(actor, groupIdValue, ruleId) {
    const groupRef = groups.doc(groupId(groupIdValue));
    const stateRef = states.doc(groupRef.id);
    if (ruleId !== undefined) id(ruleId);
    return db.runTransaction(async tx => {
      const [groupDoc, stateDoc, metaDoc] = await tx.getAll(groupRef, stateRef, metadata);
      owner(groupDoc.data(), actor.uid);
      const normalized = normalizedMemberships(groupDoc.data());
      let revision = revisionOf(metaDoc);
      const data = stateDoc.data() ?? { rules: {}, tombstones: [] };
      let result;
      if (ruleId !== undefined) {
        const rule = Object.hasOwn(data.rules, ruleId) ? data.rules[ruleId] : null;
        if (!rule) fail(404, 'not_found', 'Managed rule not found.');
        result = { rule };
      } else result = { rules: Object.values(data.rules).sort(byId), tombstones: data.tombstones.map(publicTombstone) };
      if (normalized.changed) {
        revision = advanceRevision(tx, metadata, metaDoc, now());
        tx.update(groupRef, { members: normalized.group.members });
      }
      return { revision, ...result };
    });
  }

  async function mutate(actor, groupIdValue, ruleIdValue, body, operation) {
    objectBody(body, operation === 'create' ? ['rule'] : operation === 'update' ? ['expectedRevision', 'rule'] : ['expectedRevision']);
    const expectedRevision = operation === 'create' ? null : expected(body.expectedRevision);
    const groupRef = groups.doc(groupId(groupIdValue));
    const stateRef = states.doc(groupRef.id);
    const ruleId = operation === 'create' ? randomUUID() : id(ruleIdValue);
    return db.runTransaction(async tx => {
      const [groupDoc, stateDoc, metaDoc] = await tx.getAll(groupRef, stateRef, metadata);
      const group = groupDoc.data();
      owner(group, actor.uid);
      const data = stateDoc.data() ?? { rules: {}, tombstones: [] };
      const rules = { ...data.rules };
      const existing = Object.hasOwn(rules, ruleId) ? rules[ruleId] : null;
      if (operation !== 'create') {
        if (!existing) {
          const tombstone = data.tombstones.find(item => item.id === ruleId);
          if (operation === 'delete' && tombstone?.deletedRevision === expectedRevision) {
            return { revision: revisionOf(metaDoc), tombstone: publicTombstone(tombstone) };
          }
          fail(404, 'not_found', 'Managed rule not found.');
        }
        if (existing.revision !== expectedRevision) fail(409, 'revision_conflict', 'The rule changed. Refresh before editing.');
      } else if (Object.keys(rules).length >= MAX_MANAGED_RULES) fail(409, 'managed_rule_limit', 'This group has reached its managed rule limit.');
      const parsed = operation === 'delete' ? null : ruleInput(body.rule, group);
      const timestamp = now();
      const revision = advanceRevision(tx, metadata, metaDoc, timestamp);
      const normalized = normalizedMemberships(group);
      if (normalized.changed) tx.update(groupRef, { members: normalized.group.members });
      let tombstones = data.tombstones;
      let result;
      if (operation === 'delete') {
        delete rules[ruleId];
        const tombstone = { id: ruleId, targetGroupId: groupRef.id, revision, deletedAt: iso(timestamp),
          scope: existing.scope, deletedRevision: existing.revision };
        tombstones = [...tombstones, tombstone].slice(-MAX_TOMBSTONES);
        result = { tombstone: publicTombstone(tombstone) };
      } else {
        const rule = { ...parsed, id: ruleId, targetGroupId: groupRef.id, revision,
          createdAt: existing?.createdAt ?? iso(timestamp), updatedAt: iso(timestamp) };
        rules[ruleId] = rule;
        result = { rule };
      }
      tx.set(stateRef, { rules, tombstones });
      return { revision, ...result };
    });
  }

  return {
    listManagedRules: (actor, groupId) => ownerRead(actor, groupId),
    getManagedRule: (actor, groupId, ruleId) => ownerRead(actor, groupId, ruleId),
    createManagedRule: (actor, groupId, body) => mutate(actor, groupId, null, body, 'create'),
    updateManagedRule: (actor, groupId, ruleId, body) => mutate(actor, groupId, ruleId, body, 'update'),
    deleteManagedRule: (actor, groupId, ruleId, body) => mutate(actor, groupId, ruleId, body, 'delete'),
    async managedState(actor) {
      return db.runTransaction(async tx => {
        const [indexDoc, metaDoc] = await tx.getAll(db.collection('userGroups').doc(actor.uid), metadata);
        const groupIds = indexDoc.data()?.groupIds ?? [];
        if (!Array.isArray(groupIds) || groupIds.length > 20 || new Set(groupIds).size !== groupIds.length) {
          fail(503, 'authority_unavailable', 'The membership index is unavailable.');
        }
        const groupRefs = groupIds.map(value => groups.doc(groupId(value)));
        const docs = groupRefs.length ? await tx.getAll(...groupRefs, ...groupIds.map(value => states.doc(value))) : [];
        const migrations = [];
        const memberships = [];
        const rules = [];
        const tombstones = [];
        for (let i = 0; i < groupIds.length; i += 1) {
          const group = docs[i].data();
          // An index/group mismatch cannot become an authoritative empty snapshot.
          if (!group || !Object.hasOwn(group.members, actor.uid)) fail(503, 'authority_unavailable', 'The membership index is unavailable.');
          membership(group, actor.uid);
          const normalized = normalizedMemberships(group);
          if (normalized.changed) migrations.push({ ref: groupRefs[i], members: normalized.group.members });
          const member = normalized.group.members[actor.uid];
          memberships.push({ groupId: groupIds[i], membershipId: member.membershipId, role: member.role });
          const state = docs[groupIds.length + i].data() ?? { rules: {}, tombstones: [] };
          rules.push(...Object.values(state.rules).filter(rule => applicable(rule, group, actor.uid)));
          tombstones.push(...state.tombstones.filter(rule => applicable(rule, group, actor.uid)).map(publicTombstone));
        }
        const timestamp = now();
        let revision = revisionOf(metaDoc);
        if (migrations.length) {
          revision = advanceRevision(tx, metadata, metaDoc, timestamp);
          for (const migration of migrations) tx.update(migration.ref, { members: migration.members });
        }
        const response = { schemaVersion: 1, complete: true, userId: actor.uid, revision, serverTime: iso(timestamp),
          memberships: memberships.sort((a, b) => a.groupId.localeCompare(b.groupId)), rules: rules.sort(byId), tombstones: tombstones.sort(byId) };
        if (Buffer.byteLength(JSON.stringify(response)) > MANAGED_RESPONSE_LIMIT) fail(503, 'authority_unavailable', 'The managed snapshot exceeds its bounded size.');
        return response;
      });
    },
  };
}
