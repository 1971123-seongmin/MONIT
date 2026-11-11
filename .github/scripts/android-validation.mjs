import { execFileSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const targets = {
  assemble: ['assembleDebug'],
  tests: [':core:model:test', 'testDebugUnitTest'],
  lint: ['lintDebug'],
};

export function validationTasks(scope) {
  if (!Object.hasOwn(targets, scope)) throw new Error('Invalid Android validation scope');
  return [...targets[scope]];
}

export function runAndroidValidation(scope, run = execFileSync) {
  const tasks = validationTasks(scope);
  run('bash', ['./gradlew', ...tasks, '--parallel', '--max-workers=2', '--console=plain'], { stdio: 'inherit' });
  return tasks;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  runAndroidValidation(process.env.MONIT_VALIDATION_SCOPE);
}
