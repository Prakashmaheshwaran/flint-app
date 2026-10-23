export const PROJECT_ID = 'demo-flint';
export const LOCAL_ENV = Object.freeze({
  GCLOUD_PROJECT: PROJECT_ID,
  GOOGLE_CLOUD_PROJECT: PROJECT_ID,
  FIREBASE_AUTH_EMULATOR_HOST: '127.0.0.1:9099',
  FIRESTORE_EMULATOR_HOST: '127.0.0.1:8085',
  METADATA_SERVER_DETECTION: 'none',
});

/** Refuse cloud credentials and any host/project drift before SDK initialization. */
export function assertEmulatorConfig(env = process.env) {
  for (const [key, value] of Object.entries(LOCAL_ENV)) {
    if (env[key] !== value) throw new Error(`Emulator-only service requires ${key}=${value}`);
  }
  if (env.GOOGLE_APPLICATION_CREDENTIALS || env.NODE_ENV === 'production') {
    throw new Error('Emulator-only service refuses production mode and credential files');
  }
  if (env.FIREBASE_CONFIG) {
    let config;
    try { config = JSON.parse(env.FIREBASE_CONFIG); } catch { throw new Error('Refusing external Firebase configuration'); }
    if (config.projectId !== PROJECT_ID) throw new Error('Refusing a non-demo Firebase configuration');
  }
}

export function localEnvironment(env = process.env) {
  for (const [key, value] of Object.entries(LOCAL_ENV)) {
    if (env[key] && env[key] !== value) throw new Error(`Refusing conflicting ${key}`);
  }
  const result = { ...env, ...LOCAL_ENV, FIREBASE_CLI_DISABLE_USAGE: 'true' };
  assertEmulatorConfig(result);
  return result;
}
