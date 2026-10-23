import { spawn } from 'node:child_process';
import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { localEnvironment } from '../src/config.mjs';

export const root = dirname(dirname(fileURLToPath(import.meta.url)));

export function emulatorEnvironment() {
  const env = localEnvironment();
  // Reuse the repository machine's installed JDK; never install or change system Java.
  const javaHomes = [env.JAVA_HOME, '/opt/homebrew/opt/openjdk@21', '/opt/homebrew/opt/openjdk', '/usr/lib/jvm/java-21-openjdk-amd64'].filter(Boolean);
  const javaHome = javaHomes.find(path => existsSync(join(path, 'bin/java')));
  if (javaHome) { env.JAVA_HOME = javaHome; env.PATH = `${join(javaHome, 'bin')}:${env.PATH}`; }
  return env;
}

export function runWithEmulators(command) {
  const env = emulatorEnvironment();
  const args = [join(root, 'node_modules/firebase-tools/lib/bin/firebase.js'),
    'emulators:exec', '--project', 'demo-flint', '--only', 'auth,firestore', '--config', join(root, 'firebase.json')];
  args.push(command);
  const child = spawn(process.execPath, args,
  { cwd: root, env, stdio: 'inherit', detached: true });
  let stopping = false;
  for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => {
    if (!stopping) { stopping = true; try { process.kill(-child.pid, signal); } catch {} }
  });
  child.on('error', error => { console.error('Cannot start local emulator process:', error.code); process.exitCode = 1; });
  child.on('exit', code => { process.exitCode = code ?? 1; });
}
