import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const targets = {
  assemble: [':app:assembleDebug', ':app:assembleQa', ':app:assemblePerformanceQa', ':app:assemblePerformanceQaAndroidTest'],
  tests: [':core:model:test', 'testDebugUnitTest', ':core:data:testPerformanceQaUnitTest', ':app:testPerformanceQaUnitTest'],
  lint: ['ktlintCheck', 'lintDebug', ':app:lintPerformanceQa'],
};

export function validationTasks(scope) {
  if (!Object.hasOwn(targets, scope)) throw new Error('Invalid Android validation scope');
  return [...targets[scope]];
}

export function runAndroidValidation(scope, run = execFileSync, { heap, gcLog } = {}) {
  const tasks = validationTasks(scope);
  if (heap && !['2g', '4g'].includes(heap)) throw new Error('Invalid Gradle heap');
  if (gcLog && (!heap || !path.isAbsolute(gcLog) || /[\s:]/.test(gcLog))) throw new Error('Invalid GC log path');
  const jvm = heap ? [`-Dorg.gradle.jvmargs=-Xmx${heap} -Dfile.encoding=UTF-8${gcLog ? ` -Xlog:gc*:file=${gcLog}:uptime,level,tags` : ''}`,
    '-Pkotlin.daemon.jvmargs=-Xmx2g'] : [];
  const workers = scope === 'assemble' ? 4 : 2;
  run('bash', ['./gradlew', ...tasks, '--parallel', `--max-workers=${workers}`, '--console=plain', ...jvm], { stdio: 'inherit' });
  return tasks;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const scope = process.env.MONIT_VALIDATION_SCOPE;
  const heap = process.env.MONIT_GRADLE_HEAP;
  const diagnostics = process.env.MONIT_GC_DIAGNOSTICS === 'true';
  const directory = diagnostics ? path.join(process.env.RUNNER_TEMP, 'monit-gradle-gc') : undefined;
  if (directory) fs.mkdirSync(directory, { recursive: true });
  const started = Date.now();
  try {
    runAndroidValidation(scope, execFileSync, { heap, gcLog: directory ? path.join(directory, 'gradle-%p.log') : undefined });
  } finally {
    if (directory) {
      let pauseMs = 0;
      let pauses = 0;
      for (const file of fs.readdirSync(directory).filter(name => /^gradle-\d+\.log$/.test(name))) {
        const log = fs.readFileSync(path.join(directory, file), 'utf8');
        for (const match of log.matchAll(/GC\(\d+\).*Pause[^\n]*?\s([\d.]+)ms\s*$/gm)) {
          pauseMs += Number(match[1]); pauses++;
        }
      }
      const elapsedSeconds = (Date.now() - started) / 1000;
      console.log(`CI_GC_SUMMARY ${JSON.stringify({ scope, heap, elapsedSeconds, pauses,
        stopTheWorldPauseSeconds: pauseMs / 1000,
        pausePercentOfCommand: elapsedSeconds ? pauseMs / (elapsedSeconds * 10) : null,
        note: 'Gradle JVM pause wall time only; excludes concurrent GC and Kotlin/test JVMs' })}`);
    }
  }
}
