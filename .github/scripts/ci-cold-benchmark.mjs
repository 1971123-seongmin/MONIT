import { spawn, execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const workers = process.env.MONIT_WORKERS;
if (!['2', '4'].includes(workers)) throw new Error('Only 2 or 4 workers are allowed');
const revision = execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim();
if (revision !== process.env.MONIT_PRODUCT_SHA) throw new Error('Unexpected product revision');
const gradleHome = process.env.GRADLE_USER_HOME;
if (process.env.MONIT_COLD_HOME_VERIFIED !== 'true' ||
    gradleHome !== path.join(process.env.RUNNER_TEMP, 'monit-cold-gradle-home')) {
  throw new Error('Gradle User Home must be verified empty before setup-gradle initializes it');
}

const results = [];
let peakVmUsedMiB = 0;
const cpuTicks = () => fs.readFileSync('/proc/stat', 'utf8').split('\n')[0]
  .trim().split(/\s+/).slice(1).map(Number);
const beforeCpu = cpuTicks();
function sampleMemory() {
  const fields = Object.fromEntries(fs.readFileSync('/proc/meminfo', 'utf8')
    .split('\n').filter(Boolean).map(line => {
      const [key, value] = line.split(':');
      return [key, Number(value.trim().split(/\s+/)[0])];
    }));
  peakVmUsedMiB = Math.max(peakVmUsedMiB, (fields.MemTotal - fields.MemAvailable) / 1024);
}
sampleMemory();
const memoryTimer = setInterval(sampleMemory, 1000);
const plainText = html => html.replace(/<[^>]*>/g, '').replace(/&nbsp;/g, ' ')
  .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&').trim();
function seconds(value) {
  const match = /^(?:(\d+)h)?(?:(\d+)m)?(\d+(?:\.\d+)?)s$/.exec(value.replace(/\s/g, ''));
  return match ? Number(match[1] ?? 0) * 3600 + Number(match[2] ?? 0) * 60 + Number(match[3]) : null;
}
function profileSummary(started) {
  const directory = path.resolve('build/reports/profile');
  if (!fs.existsSync(directory)) return { available: false };
  const report = fs.readdirSync(directory).filter(name => /^profile-.*\.html$/.test(name))
    .map(name => ({ file: path.join(directory, name), modified: fs.statSync(path.join(directory, name)).mtimeMs }))
    .filter(item => item.modified >= started - 1000).sort((a, b) => b.modified - a.modified)[0];
  if (!report) return { available: false };
  const html = fs.readFileSync(report.file, 'utf8');
  const rows = [...html.matchAll(/<tr\b[^>]*>([\s\S]*?)<\/tr>/g)].map(row =>
    [...row[1].matchAll(/<t[dh]\b[^>]*>([\s\S]*?)<\/t[dh]>/g)].map(cell => plainText(cell[1])));
  const tasks = rows.filter(row => row[0]?.startsWith(':') && seconds(row[1] ?? '') !== null)
    .map(row => ({ task: row[0], seconds: seconds(row[1]) })).sort((a, b) => b.seconds - a.seconds).slice(0, 10);
  const phases = rows.filter(row => /^(Total Build Time|Startup|Settings and buildSrc|Loading Projects|Configuring Projects|Task Execution)$/.test(row[0] ?? ''));
  return { available: true, phases, longestTasks: tasks };
}
async function runGradle(label, args) {
  const started = Date.now();
  console.log(`BENCHMARK_START ${label}`);
  let exitCode;
  try {
    exitCode = await new Promise((resolve, reject) => {
      const child = spawn('bash', ['./gradlew', ...args, '--console=plain', '--profile'], { stdio: 'inherit' });
      child.once('error', reject);
      child.once('close', (code, signal) => signal ? reject(new Error(`Gradle terminated by ${signal}`)) : resolve(code));
    });
  } finally {
    const result = { label, wallSeconds: (Date.now() - started) / 1000, exitCode: exitCode ?? null,
      profile: profileSummary(started) };
    results.push(result);
    console.log(`BENCHMARK_RESULT ${JSON.stringify(result)}`);
  }
  if (exitCode !== 0) throw new Error(`${label} failed with exit code ${exitCode}`);
}

try {
  for (const family of ['button', 'chip', 'textfield']) {
    await runGradle(`independent-${family}`, [':core:designsystem:compileDebugKotlin',
      '--init-script', 'scripts/verify-component.init.gradle.kts', `-Pmonit.component=${family}`,
      '-Pkotlin.incremental=false', '--project-cache-dir',
      `core/designsystem/build/component-compilation/project-cache/${family}`, '--max-workers=2']);
  }
  await runGradle('build-tests-lint', [':core:model:test', 'assembleDebug', 'testDebugUnitTest', 'lintDebug',
    '--parallel', `--max-workers=${workers}`]);
} finally {
  clearInterval(memoryTimer);
  sampleMemory();
  const afterCpu = cpuTicks();
  // Linux guest ticks are already included in user/nice: sum only the first eight counters.
  const delta = afterCpu.slice(0, 8).map((value, index) => value - beforeCpu[index]);
  const total = delta.reduce((sum, value) => sum + value, 0);
  const summary = { revision, workers: Number(workers), restoredOrSavedGithubCache: false,
    peakVmUsedMiB: Math.round(peakVmUsedMiB),
    averageVmCpuBusyPercent: total ? Math.round((1 - (delta[3] + delta[4]) / total) * 100) : null,
    memoryScope: 'Whole VM MemTotal - MemAvailable; not a per-JVM heap measurement', results };
  console.log(`BENCHMARK_SUMMARY ${JSON.stringify(summary)}`);
  if (process.env.GITHUB_STEP_SUMMARY) {
    fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, `### Cold comparison (temporary harness; do not merge)\n\n\`\`\`json\n${JSON.stringify(summary, null, 2)}\n\`\`\`\n`);
  }
}
