import { spawn } from 'node:child_process';
import { existsSync, mkdirSync, chmodSync, openSync, closeSync } from 'node:fs';
import { join } from 'node:path';
import { createServer } from 'node:net';
import { setTimeout as delay } from 'node:timers/promises';
import { emulatorEnvironment, root } from './runner.mjs';

const env = emulatorEnvironment();
process.umask(0o077);
// Never attach to, replace, or export an unrelated already-running emulator/service.
await Promise.all([8787, 9099, 8085, 4400, 4500, 9150].map(port => new Promise((resolve, reject) => {
  const probe = createServer();
  probe.once('error', () => reject(new Error(`Local port ${port} is already occupied; stop this launch without changing that service.`)));
  probe.listen(port, '127.0.0.1', () => probe.close(resolve));
})));
const state = join(root, '.emulator-data');
mkdirSync(state, { recursive: true, mode: 0o700 });
chmodSync(state, 0o700);
const args = [join(root, 'node_modules/firebase-tools/lib/bin/firebase.js'), 'emulators:start',
  '--project', 'demo-flint', '--only', 'auth,firestore', '--config', join(root, 'firebase.json'), '--export-on-exit', state];
if (existsSync(join(state, 'firebase-export-metadata.json'))) args.push('--import', state);

const logs = join(root, 'logs');
mkdirSync(logs, { recursive: true, mode: 0o700 });
chmodSync(logs, 0o700);
const run = `${new Date().toISOString().replaceAll(':', '-')}-${process.pid}`;

function child(commandArgs, label) {
  // Separate process groups prevent a terminal Ctrl-C from double-signaling exporters.
  // Private files keep detached Firebase exporters alive if an npm wrapper closes the PTY.
  // Firebase logs can contain emulator password-reset links; never commit these files.
  const logPath = join(logs, `${run}-${label}.log`);
  const log = openSync(logPath, 'a', 0o600);
  const childProcess = spawn(process.execPath, commandArgs, { cwd: root, env, stdio: ['ignore', log, log], detached: true });
  const exited = new Promise(resolve => {
    let closed = false;
    const finish = code => { if (!closed) { closed = true; closeSync(log); resolve(code); } };
    childProcess.once('error', () => finish(1));
    childProcess.once('exit', code => finish(code ?? 1));
  });
  console.log(`Local ${label} log: ${logPath}`);
  return { process: childProcess, exited };
}

const emulators = child(args, 'emulators');
let api;
let stopping;
let emulatorExited = false;
const stop = code => {
  if (stopping) return stopping;
  stopping = (async () => {
    if (api && api.process.exitCode === null) { api.process.kill('SIGTERM'); await api.exited; }
    if (!emulatorExited) { emulators.process.kill('SIGINT'); await emulators.exited; }
    if (existsSync(state)) chmodSync(state, 0o700);
    process.exitCode = code;
  })();
  return stopping;
};
// npm/terminal teardown may deliver multiple signals; none may interrupt the export.
for (const signal of ['SIGINT', 'SIGTERM', 'SIGHUP']) process.on(signal, () => { void stop(0); });
void emulators.exited.then(code => {
  emulatorExited = true;
  if (!stopping) void stop(code || 1);
});

try {
  let ready = false;
  for (let attempt = 0; attempt < 150 && !stopping; attempt += 1) {
    try {
      const response = await fetch('http://127.0.0.1:4400/emulators', { signal: AbortSignal.timeout(400) });
      const services = await response.json();
      ready = services.auth?.port === 9099 && services.firestore?.port === 8085;
    } catch {}
    if (ready) break;
    await delay(300);
  }
  if (!stopping) {
    if (!ready) throw new Error('Local emulators did not become ready');
    api = child([join(root, 'src/server.mjs')], 'api');
    void api.exited.then(code => { if (!stopping) void stop(code || 1); });
  }
} catch (error) {
  console.error(error.message);
  await stop(1);
}
