import fs from 'node:fs';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';

const uuid = '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}';
const runPattern = new RegExp(`^${uuid}$`);
const filePattern = new RegExp(`^[a-z-]+-${uuid}\\.jsonl$`);
const limit = 16 * 1024 * 1024;

// Accept only the signed instrumentation's bounded raw-evidence channel, never shell paths.
export function decodeProbeArtifacts(log) {
  const pending = new Map();
  const complete = [];
  const finished = new Set();
  for (const line of log.split(/\r?\n/)) {
    const match = /^INSTRUMENTATION_STATUS: (probeChunk|probeFileEnd)=(.+)$/.exec(line);
    if (!match) continue;
    const value = JSON.parse(match[2]);
    if (!runPattern.test(value.runId) || !filePattern.test(value.file)) throw new Error('Invalid evidence identity');
    const key = `${value.runId}--${value.file}`;
    if (finished.has(key)) throw new Error('Repeated evidence file');
    const state = pending.get(key) ?? { chunks: [], bytes: 0 };
    if (match[1] === 'probeChunk') {
      if (value.sequence !== state.chunks.length || typeof value.base64 !== 'string' ||
          value.base64.length > 16384 || !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(value.base64)) {
        throw new Error('Missing, reordered or invalid evidence chunk');
      }
      const bytes = Buffer.from(value.base64, 'base64');
      if (!bytes.length || state.bytes + bytes.length > limit) throw new Error('Evidence size exceeds bound');
      state.chunks.push(bytes);
      state.bytes += bytes.length;
      pending.set(key, state);
    } else {
      if (!pending.has(key) || value.chunks !== state.chunks.length || value.bytes !== state.bytes) throw new Error('Incomplete evidence file');
      const bytes = Buffer.concat(state.chunks);
      if (createHash('sha256').update(bytes).digest('hex') !== value.sha256) throw new Error('Evidence hash mismatch');
      complete.push({ name: key, bytes, sha256: value.sha256 });
      pending.delete(key);
      finished.add(key);
    }
  }
  if (!complete.length || pending.size) throw new Error('No complete evidence or missing file-end record');
  return complete;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [, , logPath, outputDirectory] = process.argv;
  if (!logPath || !outputDirectory) throw new Error('Usage: node backup-probe-artifacts.mjs export.log NEW_DIRECTORY');
  const artifacts = decodeProbeArtifacts(fs.readFileSync(logPath, 'utf8'));
  fs.mkdirSync(outputDirectory); // Refuse to overwrite an existing evidence directory.
  for (const artifact of artifacts) fs.writeFileSync(path.join(outputDirectory, artifact.name), artifact.bytes, { flag: 'wx' });
  console.log(JSON.stringify(artifacts.map(({ name, bytes, sha256 }) => ({ name, bytes: bytes.length, sha256 }))));
}
