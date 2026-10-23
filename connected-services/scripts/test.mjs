import { runWithEmulators } from './runner.mjs';
runWithEmulators('node --test --test-concurrency=1 test/*.test.mjs');
