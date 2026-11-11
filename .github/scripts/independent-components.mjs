import { execFileSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const allowed = ['button', 'chip', 'textfield'];

export function runIndependentComponents(environment, run = execFileSync) {
  const components = environment.IMPACT_OUTCOME === 'success'
    ? (environment.MONIT_COMPONENTS ?? '').split(/\s+/).filter(Boolean)
    : allowed;
  if (components.some(component => !allowed.includes(component))) throw new Error('Invalid independent component');
  for (const component of new Set(components)) {
    run('bash', ['./gradlew', ':core:designsystem:compileDebugKotlin',
      '--init-script', 'scripts/verify-component.init.gradle.kts', `-Pmonit.component=${component}`,
      '-Pkotlin.incremental=false', '--project-cache-dir',
      `core/designsystem/build/component-compilation/project-cache/${component}`,
      '--max-workers=2', '--console=plain'], { stdio: 'inherit' });
  }
  return [...new Set(components)];
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  console.log(`Independent component selection: ${runIndependentComponents(process.env).join(', ') || 'none'}`);
}
