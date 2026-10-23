import { createServer } from 'node:http';
import { generateKeyPairSync } from 'node:crypto';
import { pathToFileURL } from 'node:url';
import { initializeApp, deleteApp, cert } from 'firebase-admin/app';
import { getAuth } from 'firebase-admin/auth';
import { getFirestore } from 'firebase-admin/firestore';
import { assertEmulatorConfig, PROJECT_ID } from './config.mjs';
import { ApiError, createService } from './service.mjs';

const BODY_LIMIT = 8192;
function rateLimiter(max, windowMs) {
  const entries = new Map();
  return key => {
    const now = Date.now();
    for (const [key, entry] of entries) if (entry.until <= now) entries.delete(key);
    const entry = entries.get(key) ?? { count: 0, until: now + windowMs };
    entry.count += 1;
    entries.set(key, entry);
    if (entry.count > max) throw new ApiError(429, 'rate_limited', 'Too many requests. Try again later.');
  };
}
function send(res, status, body) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff', 'Content-Security-Policy': "default-src 'none'" });
  res.end(JSON.stringify(body));
}
async function readBody(req) {
  if (Number(req.headers['content-length']) > BODY_LIMIT) throw new ApiError(413, 'body_too_large', 'Request body exceeds 8 KiB.');
  if (req.headers['content-type']?.split(';')[0].trim().toLowerCase() !== 'application/json') {
    throw new ApiError(415, 'content_type', 'Use application/json.');
  }
  let size = 0;
  const chunks = [];
  for await (const chunk of req) {
    size += chunk.length;
    if (size > BODY_LIMIT) throw new ApiError(413, 'body_too_large', 'Request body exceeds 8 KiB.');
    chunks.push(chunk);
  }
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}'); }
  catch { throw new ApiError(400, 'invalid_json', 'Request body must be valid JSON.'); }
}
export function createApi({ auth, db, clock } = {}) {
  const service = createService(db, clock ? { now: clock } : undefined);
  const ipRate = rateLimiter(300, 60_000);
  const userRate = rateLimiter(120, 60_000);
  const inviteRate = rateLimiter(20, 3_600_000);
  return createServer({ maxHeaderSize: 16_384, requestTimeout: 15_000, headersTimeout: 10_000 }, async (req, res) => {
    try {
      if (req.headers.origin) throw new ApiError(403, 'origin_rejected', 'Browser origins are not supported by this local API.');
      const url = new URL(req.url, 'http://127.0.0.1');
      if (req.method === 'GET' && url.pathname === '/health') {
        return send(res, 200, { status: 'ok', mode: 'emulator-only', projectId: PROJECT_ID });
      }
      ipRate(req.socket.remoteAddress ?? 'local');
      const header = req.headers.authorization;
      if (!header?.startsWith('Bearer ') || header.length > 12_000) throw new ApiError(401, 'unauthenticated', 'Sign in to continue.');
      let actor;
      try { actor = await auth.verifyIdToken(header.slice(7), true); }
      catch { throw new ApiError(401, 'unauthenticated', 'The session is invalid or expired. Sign in again.'); }
      userRate(actor.uid);
      if (url.search) throw new ApiError(422, 'invalid_payload', 'Query parameters are not supported.');
      const parts = url.pathname.split('/').filter(Boolean);
      if (parts.some(part => part.includes('%')) || url.pathname.endsWith('/')) throw new ApiError(404, 'not_found', 'Endpoint not found.');
      const method = req.method;
      const path = url.pathname;
      const body = ['POST', 'PATCH', 'PUT', 'DELETE'].includes(method) ? await readBody(req) : undefined;
      let result;
      let status = 200;
      if (path === '/v1/me' && method === 'GET') result = await service.me(actor);
      else if (path === '/v1/me/managed-state' && method === 'GET') result = await service.managedState(actor);
      else if (path === '/v1/me' && method === 'PATCH') result = await service.updateMe(actor, body);
      else if (path === '/v1/groups' && method === 'GET') result = await service.listGroups(actor);
      else if (path === '/v1/groups' && method === 'POST') { result = await service.createGroup(actor, body); status = 201; }
      else if (parts[0] === 'v1' && parts[1] === 'groups' && parts.length === 3 && method === 'GET') result = await service.getGroup(actor, parts[2]);
      else if (parts[0] === 'v1' && parts[1] === 'groups' && parts.length === 4 && parts[3] === 'members' && method === 'GET') result = await service.listMembers(actor, parts[2]);
      else if (parts[0] === 'v1' && parts[1] === 'groups' && parts[3] === 'managed-rules' && parts.length === 4 && method === 'GET') result = await service.listManagedRules(actor, parts[2]);
      else if (parts[0] === 'v1' && parts[1] === 'groups' && parts[3] === 'managed-rules' && parts.length === 4 && method === 'POST') {
        result = await service.createManagedRule(actor, parts[2], body); status = 201;
      } else if (parts[0] === 'v1' && parts[1] === 'groups' && parts[3] === 'managed-rules' && parts.length === 5 && method === 'GET') result = await service.getManagedRule(actor, parts[2], parts[4]);
      else if (parts[0] === 'v1' && parts[1] === 'groups' && parts[3] === 'managed-rules' && parts.length === 5 && method === 'PUT') result = await service.updateManagedRule(actor, parts[2], parts[4], body);
      else if (parts[0] === 'v1' && parts[1] === 'groups' && parts[3] === 'managed-rules' && parts.length === 5 && method === 'DELETE') result = await service.deleteManagedRule(actor, parts[2], parts[4], body);
      else if (parts[0] === 'v1' && parts[1] === 'groups' && parts.length === 4 && parts[3] === 'invites' && method === 'POST') {
        inviteRate(actor.uid);
        result = await service.createInvite(actor, parts[2], body); status = 201;
      } else if (parts[0] === 'v1' && parts[1] === 'groups' && parts.length === 4 && parts[3] === 'leave' && method === 'POST') result = await service.leaveGroup(actor, parts[2], body);
      else if (parts[0] === 'v1' && parts[1] === 'invites' && parts.length === 3 && method === 'GET') result = await service.previewInvite(actor, parts[2]);
      else if (parts[0] === 'v1' && parts[1] === 'invites' && parts.length === 4 && parts[3] === 'accept' && method === 'POST') result = await service.acceptInvite(actor, parts[2], body);
      else throw new ApiError(404, 'not_found', 'Endpoint not found.');
      send(res, status, result);
    } catch (error) {
      if (error instanceof ApiError) send(res, error.status, { error: { code: error.code, message: error.message } });
      else {
        // Never print request URLs, bearer tokens, invitation codes, or payloads.
        console.error('Local API operation failed:', error?.code ?? error?.name ?? 'unknown');
        send(res, 503, { error: { code: 'service_unavailable', message: 'The local service is temporarily unavailable.' } });
      }
    }
  });
}

export async function startApi({ port = 8787 } = {}) {
  assertEmulatorConfig();
  // This fresh, memory-only key has no cloud identity. Supplying it avoids ambient ADC
  // discovery; both SDK transports use their emulator-only authorization path.
  const { privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
  const app = initializeApp({ projectId: PROJECT_ID, credential: cert({ projectId: PROJECT_ID,
    clientEmail: 'emulator@demo-flint.invalid', privateKey: privateKey.export({ type: 'pkcs8', format: 'pem' }) }) });
  const auth = getAuth(app);
  const db = getFirestore(app);
  const server = createApi({ auth, db });
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(port, '127.0.0.1', resolve); });
  return { server, auth, db, close: async () => {
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
    await db.terminate();
    await deleteApp(app);
  } };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const api = await startApi();
  console.log('Flint local API ready at http://127.0.0.1:8787 (demo-flint emulator only)');
  for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, async () => { await api.close(); process.exit(0); });
}
