import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { checkDiff, classifyChanges, determineImpact, families, parseChanges, resolveRange } from './ci-impact.mjs';
import { runIndependentComponents } from './independent-components.mjs';
import { runAndroidValidation, validationTasks } from './android-validation.mjs';

const changed = (...paths) => classifyChanges(paths.map(file => ({ status: 'M', paths: [file] })));
const ds = 'core/designsystem/src/main/';
const kt = `${ds}kotlin/com/monit/core/designsystem/`;

test('large documentation and images do not request Android or independent compilation', () => {
  const result = changed('AGENTS.md', 'docs/architecture/navigation2-navigation-plan.md',
    'core/designsystem/README.md', 'core/designsystem/docs/example.png', '.agents/skills/write-tests/SKILL.md');
  assert.equal(result.android, false);
  assert.deepEqual(result.components, []);
});
test('one-line financial, Room, permission and Kotlin-comment changes retain Android checks', () => {
  for (const file of ['core/model/src/main/kotlin/LedgerModels.kt', 'core/data/src/main/kotlin/RoomLedgerRepository.kt',
    'app/src/main/AndroidManifest.xml', 'feature/accounts/src/test/kotlin/AccountTest.kt']) {
    assert.equal(changed(file).android, true);
    assert.deepEqual(changed(file).components, []);
  }
});
test('component source changes request only that independent family', () => {
  for (const family of families) assert.deepEqual(changed(`${kt}component/${family}/Example.kt`).components, [family]);
});
test('theme, DS resources, fonts and manifest affect all families', () => {
  for (const file of [`${kt}theme/MonitTheme.kt`, `${ds}res/values/strings.xml`,
    `${ds}assets/fonts/font.ttf`, `${ds}AndroidManifest.xml`]) assert.deepEqual(changed(file).components, families);
});

test('DS debug resources and unknown source sets fail closed to all families', () => {
  for (const file of ['core/designsystem/src/debug/res/values/colors.xml',
    'core/designsystem/src/debug/AndroidManifest.xml', 'core/designsystem/src/debug/kotlin/DebugTheme.kt',
    'core/designsystem/src/demo/res/values/colors.xml', 'core/designsystem/src/testFixtures/kotlin/Fixture.kt']) {
    assert.deepEqual(changed(file).components, families);
  }
});
test('DS preview, unrelated components and host tests retain Android but not independent compilation', () => {
  for (const file of [`${kt}preview/MonitFamilyPreviews.kt`, `${kt}component/MonitDialog.kt`,
    'core/designsystem/src/test/kotlin/MonitInteractionTest.kt']) {
    assert.equal(changed(file).android, true);
    assert.deepEqual(changed(file).components, []);
  }
});
test('common build, dependency, environment and validation inputs fail closed', () => {
  for (const file of ['gradle.properties', 'gradle/libs.versions.toml', 'gradle/wrapper/gradle-wrapper.properties',
    'settings.gradle.kts', 'buildSrc/src/main/kotlin/Config.kt', 'app/build.gradle.kts',
    'scripts/verify-component.init.gradle.kts', '.github/workflows/android-ci.yml', '.github/scripts/ci-impact.mjs']) {
    assert.deepEqual(changed(file).components, families);
    assert.equal(changed(file).ciTests, true);
  }
});
test('schema, fixtures, unknown paths and non-document extensions do not become docs-only', () => {
  for (const file of ['docs/contracts/backup-v1.schema.json', 'docs/contracts/fixtures/input.json',
    'new-module/source.kt', 'docs/command.sh']) assert.deepEqual(changed(file).components, families);
});
test('README and doc-shaped runtime assets remain Android inputs', () => {
  for (const file of ['app/src/main/assets/README.md', 'app/src/main/assets/docs/help.png',
    `${ds}assets/docs/help.svg`]) assert.equal(changed(file).android, true);
});
test('deleted source and both rename endpoints affect the scope', () => {
  const records = parseChanges(`D\0${kt}component/button/Old.kt\0R100\0${kt}component/chip/Old.kt\0docs/moved.md\0`);
  assert.deepEqual(classifyChanges(records).components, ['button', 'chip']);
  assert.equal(classifyChanges(parseChanges('R100\0docs/old.md\0app/src/main/new.kt\0')).android, true);
  assert.equal(classifyChanges(parseChanges('R100\0docs/old.md\0docs/new.md\0')).android, false);
});
test('NUL framing preserves hostile filenames without shell evaluation', () => {
  const filename = 'app/src/main/$(touch hacked)\nvalue.kt';
  assert.deepEqual(parseChanges(`M\0${filename}\0`), [{ status: 'M', paths: [filename] }]);
  assert.equal(changed(filename).android, true);
});
test('malformed, unknown and file-type statuses are rejected', () => {
  for (const diff of ['M\0missing-final-null', 'R100\0one\0', 'T\0docs/link.md\0', 'U\0conflict\0']) {
    assert.throws(() => parseChanges(diff));
  }
});
test('empty resolved diff is docs-only', () => assert.equal(classifyChanges(parseChanges('')).android, false));

test('successful empty component output launches no Gradle process', () => {
  const calls = [];
  runIndependentComponents({ IMPACT_OUTCOME: 'success', MONIT_COMPONENTS: '' }, (...args) => calls.push(args));
  assert.equal(calls.length, 0);
});
test('selected component is passed as an argument with nonincremental and isolation guards', () => {
  const calls = [];
  runIndependentComponents({ IMPACT_OUTCOME: 'success', MONIT_COMPONENTS: 'chip' }, (...args) => calls.push(args));
  assert.equal(calls.length, 1);
  assert.equal(calls[0][0], 'bash');
  assert(calls[0][1].includes('-Pmonit.component=chip'));
  assert(calls[0][1].includes('-Pkotlin.incremental=false'));
  assert(calls[0][1].includes('core/designsystem/build/component-compilation/project-cache/chip'));
});
test('classifier process failure requests all independent checks; invalid components cannot become shell code', () => {
  const calls = [];
  runIndependentComponents({ IMPACT_OUTCOME: 'failure', MONIT_COMPONENTS: '' }, (...args) => calls.push(args));
  assert.equal(calls.length, 3);
  assert.throws(() => runIndependentComponents({ IMPACT_OUTCOME: 'success', MONIT_COMPONENTS: '$(touch hacked)' }));
});
test('workflow passes empty outputs directly to the tested adapter instead of truthy fallback', () => {
  const workflow = fs.readFileSync(new URL('../workflows/android-ci.yml', import.meta.url), 'utf8');
  assert(workflow.includes('MONIT_COMPONENTS: ${{ steps.impact.outputs.components }}'));
  assert(workflow.includes("if: matrix.scope == 'independent'"));
  assert(workflow.includes('run: node .github/scripts/independent-components.mjs'));
});

test('parallel scopes build the app and preserve all-module unit tests and lint', () => {
  const tasks = ['assemble', 'tests', 'lint'].flatMap(validationTasks);
  assert.deepEqual(tasks.sort(), [':core:model:test', ':app:assembleDebug', ':app:assembleQa', ':app:assemblePerformanceQa', ':app:assemblePerformanceQaAndroidTest',
    'testDebugUnitTest', ':core:data:testPerformanceQaUnitTest', ':app:testPerformanceQaUnitTest', 'ktlintCheck', 'lintDebug', ':app:lintPerformanceQa'].sort());
});

test('each Android scope uses fixed arguments and preserves bounded workers', () => {
  for (const [scope, workerArgument] of [['assemble', '--max-workers=4'], ['tests', '--max-workers=2'], ['lint', '--max-workers=2']]) {
    const calls = [];
    runAndroidValidation(scope, (...args) => calls.push(args));
    assert.equal(calls.length, 1);
    assert.equal(calls[0][0], 'bash');
    assert.deepEqual(calls[0][1], ['./gradlew', ...validationTasks(scope), '--parallel', workerArgument, '--console=plain']);
  }
});

test('invalid scopes cannot launch a command or select inherited object properties', () => {
  let calls = 0;
  for (const scope of ['', undefined, 'independent', 'constructor', '__proto__', '$(touch hacked)']) {
    assert.throws(() => runAndroidValidation(scope, () => { calls++; }));
  }
  assert.equal(calls, 0);
});

test('Gradle validation failures reach the required job', () => {
  assert.throws(() => runAndroidValidation('tests', () => { throw new Error('Test failure'); }), /Test failure/);
});

test('CI heap diagnostics are bounded arguments and keep the Kotlin daemon unchanged', () => {
  const calls = [];
  runAndroidValidation('assemble', (...args) => calls.push(args), { heap: '4g', gcLog: '/tmp/gradle-%p.log' });
  assert(calls[0][1].includes('-Dorg.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8 -Xlog:gc*:file=/tmp/gradle-%p.log:uptime,level,tags'));
  assert(calls[0][1].includes('-Pkotlin.daemon.jvmargs=-Xmx2g'));
  for (const heap of ['8g', '4g -Dunexpected=true']) {
    assert.throws(() => runAndroidValidation('tests', () => {}, { heap }));
  }
  assert.throws(() => runAndroidValidation('tests', () => {}, { heap: '2g', gcLog: '/tmp/unexpected path' }));
});

test('required build waits for every matrix result and only accepts success', () => {
  const workflow = fs.readFileSync(new URL('../workflows/android-ci.yml', import.meta.url), 'utf8');
  assert(workflow.includes('scope: [assemble, tests, lint, independent]'));
  assert(workflow.includes('needs: validation'));
  assert(workflow.includes('if: ${{ always() }}'));
  assert(workflow.includes('VALIDATION_RESULT: ${{ needs.validation.result }}'));
  assert(workflow.includes('run: test "$VALIDATION_RESULT" = success'));
});

test('a classifier failure overrides even a previously written false output', () => {
  const workflow = fs.readFileSync(new URL('../workflows/android-ci.yml', import.meta.url), 'utf8');
  assert(workflow.includes("if: matrix.scope != 'independent' && (steps.impact.outcome != 'success' || steps.impact.outputs.android != 'false')"));
  assert(workflow.includes("if: matrix.scope == 'independent' && (steps.impact.outcome != 'success' || steps.impact.outputs.ci_tests != 'false')"));
});

const base = 'a'.repeat(40), head = 'b'.repeat(40), merge = 'c'.repeat(40);
const context = { eventName: 'pull_request', event: { pull_request: { base: { sha: base }, head: { sha: head } } }, sha: merge };
const fakeGit = (diff = 'M\0docs/example.md\0') => args => {
  if (args[0] === 'rev-parse') return (args[1] === 'HEAD' ? merge : args[2].slice(0, 40)) + '\n';
  if (args[0] === 'show') return `${base} ${head}\n`;
  if (args[0] === 'diff') return diff;
  throw new Error('Unexpected command');
};
test('PR scope uses actual merge tree against verified base, not the last commit', () => {
  assert.deepEqual(resolveRange(context, fakeGit()), { base, target: merge, head });
  assert.equal(determineImpact(context, fakeGit()).android, false);
});
test('main push uses entire before-after range', () => {
  const event = { before: base, after: merge };
  assert.deepEqual(resolveRange({ eventName: 'push', event, sha: merge, ref: 'refs/heads/main' }, fakeGit()),
    { base, target: merge, head: merge });
});
test('manual event, missing history, zero SHA, moved base and broken diff select all checks', () => {
  const cases = [
    [{ ...context, eventName: 'workflow_dispatch' }, fakeGit()],
    [context, () => { throw new Error('Missing history'); }],
    [{ ...context, event: { pull_request: { base: { sha: '0'.repeat(40) }, head: { sha: head } } } }, fakeGit()],
    [context, args => args[0] === 'show' ? `${head} ${base}\n` : fakeGit()(args)],
    [context, fakeGit('T\0docs/link.md\0')],
  ];
  for (const [input, runGit] of cases) assert.deepEqual(determineImpact(input, runGit).components, families);
});
test('revision strings cannot inject git options or shell commands', () => {
  assert.deepEqual(determineImpact({ ...context, sha: '--output=/tmp/hacked' }, fakeGit()).components, families);
});

test('whitespace checks use the whole verified range and propagate unresolved PR/push errors', () => {
  const calls = [];
  checkDiff(context, args => { calls.push(args); return fakeGit()(args); });
  assert.deepEqual(calls.at(-1), ['diff', '--check', base, merge, '--']);
  for (const eventName of ['pull_request', 'push']) {
    assert.throws(() => checkDiff({ ...context, eventName }, () => { throw new Error('Missing history'); }));
  }
  assert.throws(() => checkDiff(context, args => {
    if (args[0] === 'diff') throw new Error('Whitespace');
    return fakeGit()(args);
  }));
});

test('only manual whitespace checks use the first-parent single-commit fallback', () => {
  const calls = [];
  checkDiff({ eventName: 'workflow_dispatch' }, args => calls.push(args));
  assert.deepEqual(calls, [['show', '--first-parent', '--format=', '--check', 'HEAD', '--']]);
  assert.throws(() => checkDiff({ eventName: 'unsupported' }, () => ''));
});
test('real Git rename/deletion output and PR merge parents are parsed correctly', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'monit-ci-impact-'));
  const run = args => execFileSync('git', args, { cwd: dir, encoding: 'utf8' });
  try {
    run(['init', '-b', 'main']);
    run(['config', 'user.name', 'CI Fixture']);
    run(['config', 'user.email', 'fixture@example.invalid']);
    fs.mkdirSync(path.join(dir, 'docs'));
    fs.writeFileSync(path.join(dir, 'docs/old.md'), 'unchanged content\n');
    run(['add', 'docs/old.md']); run(['commit', '-m', 'base']);
    const original = run(['rev-parse', 'HEAD']).trim();
    run(['switch', '-c', 'fixture']);
    run(['mv', 'docs/old.md', 'docs/new.md']); run(['commit', '-m', 'rename']);
    const feature = run(['rev-parse', 'HEAD']).trim();
    run(['switch', 'main']); run(['merge', '--no-ff', 'fixture', '-m', 'merge']);
    const merged = run(['rev-parse', 'HEAD']).trim();
    const result = determineImpact({ eventName: 'pull_request', sha: merged,
      event: { pull_request: { base: { sha: original }, head: { sha: feature } } } }, run);
    assert.equal(result.android, false);
    assert.equal(result.changedPaths, 2);
    assert.equal(result.base, original);
  } finally {
    // Only remove the exact fixture directory allocated by mkdtemp, including its local Git metadata.
    const actual = fs.realpathSync(dir);
    const relative = path.relative(fs.realpathSync(os.tmpdir()), actual);
    assert(!relative.startsWith('..') && !path.isAbsolute(relative));
    assert(path.basename(actual).startsWith('monit-ci-impact-'));
    fs.rmSync(dir, { recursive: true, force: true });
  }
});
