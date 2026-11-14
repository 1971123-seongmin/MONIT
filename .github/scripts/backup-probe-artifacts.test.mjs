import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import test from 'node:test';
import { decodeProbeArtifacts } from './backup-probe-artifacts.mjs';

const identity = { runId: '72000000-0000-0000-0000-000000000001', file: 'crypto-72000000-0000-0000-0000-000000000002.jsonl' };
const bytes = Buffer.from('{"kind":"summary","status":"SMOKE_ONLY","한글":true}\n');
const chunk = { ...identity, sequence: 0, base64: bytes.toString('base64') };
const end = { ...identity, chunks: 1, bytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') };
const line = (kind, value) => `INSTRUMENTATION_STATUS: ${kind}=${JSON.stringify(value)}\r\n`;

test('raw evidence round trips with exact UTF-8 bytes and hash', () => {
  const result = decodeProbeArtifacts('Other instrumentation output\r\n' + line('probeChunk', chunk) + line('probeFileEnd', end));
  assert.equal(result.length, 1);
  assert.deepEqual(result[0].bytes, bytes);
  assert.equal(result[0].sha256, end.sha256);
});

test('missing chunks, duplicate sequence, missing end and wrong hash are rejected', () => {
  assert.throws(() => decodeProbeArtifacts(line('probeChunk', { ...chunk, sequence: 1 })));
  assert.throws(() => decodeProbeArtifacts(line('probeChunk', chunk) + line('probeChunk', chunk)));
  assert.throws(() => decodeProbeArtifacts(line('probeChunk', chunk)));
  assert.throws(() => decodeProbeArtifacts(line('probeChunk', chunk) + line('probeFileEnd', { ...end, sha256: '0'.repeat(64) })));
  assert.throws(() => decodeProbeArtifacts(line('probeFileEnd', end)));
});

test('paths, oversized chunks and repeated file-end records cannot be accepted', () => {
  assert.throws(() => decodeProbeArtifacts(line('probeChunk', { ...chunk, file: '../key.jsonl' })));
  assert.throws(() => decodeProbeArtifacts(line('probeChunk', { ...chunk, base64: 'A'.repeat(16388) })));
  assert.throws(() => decodeProbeArtifacts(line('probeChunk', chunk) + line('probeFileEnd', end) + line('probeFileEnd', end)));
});
