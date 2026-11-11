import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const families = ['button', 'chip', 'textfield'];
const shaPattern = /^[0-9a-f]{40}$/;
const ds = 'core/designsystem/';
const sourceRoot = `${ds}src/main/kotlin/com/monit/core/designsystem/`;

export function fullValidation(reason) {
  return { android: true, components: [...families], ciTests: true, reason };
}

export function parseChanges(output) {
  if (output === '') return [];
  const fields = output.split('\0');
  if (fields.pop() !== '') throw new Error('Unterminated Git diff');
  const records = [];
  for (let index = 0; index < fields.length;) {
    const status = fields[index++];
    if (!/^(?:[ADM]|[RC]\d+)$/.test(status)) throw new Error('Unknown Git status');
    const count = /^[RC]/.test(status) ? 2 : 1;
    const paths = fields.slice(index, index + count);
    if (paths.length !== count || paths.some(value => !value)) throw new Error('Missing diff path');
    records.push({ status, paths });
    index += count;
  }
  return records;
}

function isDocumentation(file) {
  // Markdown/images inside a source set may be packaged runtime assets.
  if (/(?:^|\/)src\//.test(file)) return false;
  return file === 'AGENTS.md' || /(^|\/)README\.md$/.test(file) ||
    /^(?:docs\/|\.agents\/skills\/).*\.md$/.test(file) ||
    /^(?:.*\/)?docs\/.*\.(?:png|jpe?g|svg|webp|gif)$/.test(file) ||
    file === '.github/pull_request_template.md' ||
    /^\.github\/ISSUE_TEMPLATE\/.*\.md$/.test(file) ||
    /^(?:LICENSE|NOTICE)(?:\.txt|\.md)?$/.test(file);
}

export function classifyChanges(records) {
  let android = false;
  let ciTests = false;
  const components = new Set();
  for (const record of records) {
    // Examine both ends of a rename: moving source into docs is still a source deletion.
    for (const file of record.paths) {
      if (isDocumentation(file)) continue;
      android = true;
      const commonBuild = /(?:^|\/)(?:build|settings)\.gradle(?:\.kts)?$/.test(file) ||
        /^(?:gradle\/|buildSrc\/|build-logic\/|scripts\/|\.github\/)/.test(file) ||
        /^(?:gradle\.properties|gradlew(?:\.bat)?|local\.properties)$/.test(file) ||
        /(?:\.lockfile|gradle\.lockfile)$/.test(file);
      if (commonBuild) return fullValidation('build-or-validation-input');
      if (!/^(?:app|core\/[^/]+|feature\/[^/]+)\/src\//.test(file)) {
        return fullValidation('unknown-input');
      }
      if (file.startsWith(`${sourceRoot}theme/`) ||
          (file.startsWith(`${ds}src/main/`) && !file.startsWith(`${sourceRoot}`))) {
        families.forEach(family => components.add(family));
      }
      for (const family of families) {
        if (file.startsWith(`${sourceRoot}component/${family}/`)) components.add(family);
      }
    }
  }
  return {
    android,
    components: families.filter(family => components.has(family)),
    ciTests,
    reason: android ? 'android-input' : 'documentation-or-empty-diff',
  };
}

const git = args => execFileSync('git', args, { encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 });

export function resolveRange({ eventName, event, sha, ref }, runGit = git) {
  const verify = value => {
    if (!shaPattern.test(value ?? '') || /^0+$/.test(value)) throw new Error('Invalid revision');
    if (runGit(['rev-parse', '--verify', `${value}^{commit}`]).trim() !== value) throw new Error('Missing revision');
    return value;
  };
  verify(sha);
  if (runGit(['rev-parse', 'HEAD']).trim() !== sha) throw new Error('Unexpected checkout');
  if (eventName === 'pull_request') {
    const base = verify(event.pull_request?.base?.sha);
    const head = verify(event.pull_request?.head?.sha);
    const parents = runGit(['show', '-s', '--format=%P', sha]).trim().split(' ');
    if (parents.length !== 2 || parents[0] !== base || parents[1] !== head) throw new Error('Unexpected PR merge/base');
    return { base, target: sha, head };
  }
  if (eventName === 'push' && ref === 'refs/heads/main') {
    if (event.after !== sha) throw new Error('Unexpected push head');
    return { base: verify(event.before), target: sha, head: sha };
  }
  throw new Error('Manual or unsupported event');
}

export function determineImpact(context, runGit = git) {
  try {
    const range = resolveRange(context, runGit);
    const records = parseChanges(runGit(['diff', '--name-status', '-z', '--find-renames', range.base, range.target, '--']));
    return { ...classifyChanges(records), ...range, changedPaths: records.flatMap(record => record.paths).length };
  } catch {
    // Missing history, unsupported status, manual events, or classifier errors must never skip checks.
    return fullValidation('manual-or-unresolved-diff');
  }
}

function contextFromEnvironment() {
  return {
    eventName: process.env.GITHUB_EVENT_NAME,
    event: JSON.parse(fs.readFileSync(process.env.GITHUB_EVENT_PATH, 'utf8')),
    sha: process.env.GITHUB_SHA,
    ref: process.env.GITHUB_REF,
  };
}

function main() {
  if (process.argv.includes('--check-diff')) {
    let range;
    try { range = resolveRange(contextFromEnvironment()); } catch { /* Check the checked-out commit below. */ }
    if (range) git(['diff', '--check', range.base, range.target, '--']);
    else git(['show', '--format=', '--check', 'HEAD', '--']);
    return;
  }
  let result;
  try { result = determineImpact(contextFromEnvironment()); }
  catch { result = fullValidation('unreadable-event'); }
  if (process.env.GITHUB_OUTPUT) {
    fs.appendFileSync(process.env.GITHUB_OUTPUT,
      `android=${result.android}\ncomponents=${result.components.join(' ')}\nci_tests=${result.ciTests}\n`);
  }
  if (process.env.GITHUB_STEP_SUMMARY) {
    fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY,
      `### Validation scope\n- Android build/tests/lint: ${result.android}\n- Independent components: ${result.components.join(', ') || 'unchanged inputs; not requested'}\n- Reason: ${result.reason}\n- Gradle build cache preserves task input validation; independent compilation remains uncached.\n`);
  }
  console.log(JSON.stringify(result));
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();
