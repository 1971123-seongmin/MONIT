// Freeze the logical v2 row adapter to the reviewed schema7 fields, never to an arbitrary current DB.
const fs = require('node:fs');
const path = require('node:path');
const root = path.join(__dirname, '..');
const database = JSON.parse(fs.readFileSync(path.join(root, 'core/data/schemas/com.monit.core.data.LedgerDatabase/7.json'))).database;
const finance = ['accounts', 'anchors', 'transactions', 'transaction_history', 'payment_rules', 'payment_rule_versions', 'payment_items', 'payment_events', 'anchor_history'];
const evidence = ['operations', 'submissions', 'submission_references', 'submission_rejections', 'payment_rejections', 'payment_intents', 'anchor_rejections'];
const object = properties => ({ type: 'object', additionalProperties: false, required: Object.keys(properties), properties });
const array = (items, maxItems = 50000) => ({ type: 'array', maxItems, items });
const text = { type: 'string', maxLength: 16384 };
const id = { type: 'string', pattern: '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' };
const integer = { type: 'string', pattern: '^(0|-[1-9][0-9]*|[1-9][0-9]*)$', maxLength: 20 };
const nullable = value => ({ anyOf: [value, { type: 'null' }] });
const defs = {};
for (const name of [...finance, ...evidence]) {
  const entity = database.entities.find(e => e.tableName === name);
  if (!entity) throw Error('Missing reviewed table ' + name);
  const properties = {};
  for (const field of entity.fields) {
    let type = field.affinity === 'INTEGER' ? integer : text;
    if (['active', 'full', 'enabled'].includes(field.columnName)) type = { type: 'boolean' };
    if (field.affinity === 'TEXT' && /(Json)$/.test(field.columnName)) type = { type: 'string', maxLength: 8388608 };
    if (field.columnName === 'requestSha256') type = { type: 'string', pattern: '^[0-9a-f]{64}$' };
    properties[field.columnName] = field.notNull ? type : nullable(type);
  }
  defs[name] = { ...object(properties), 'x-primaryKey': entity.primaryKey.columnNames, 'x-foreignKeys': entity.foreignKeys };
}
const context = object({ ledgerId: id, storageGeneration: id });
const witness = { sourceContext: context, operationId: id, draftAvailability: { enum: ['NOT_REFERENCED', 'EXCLUDED_BY_POLICY'] } };
for (const name of evidence) witness[name] = nullable({ $ref: '#/$defs/' + name });
const preferences = object({
  coverage: { enum: ['RECORDED', 'NOT_RECORDED_BY_V1'] },
  global: object({ present: { type: 'boolean' }, enabled: nullable({ type: 'boolean' }), revision: nullable(integer) }),
  rules: array(object({ ruleId: id, enabled: { type: 'boolean' }, revision: integer })),
});
const original = object({ id, sourceNamespace: text, fileName: text, byteLength: integer, sha256: { type: 'string', pattern: '^[0-9a-f]{64}$' }, bytesBase64: { type: 'string', maxLength: 2796204 } });
const counts = Object.fromEntries([...finance, ...evidence, 'operationFences', 'reminderGlobal', 'reminderRules'].map(name => [name, { type: 'integer', minimum: 0, maximum: 50000 }]));
const schema = {
  $schema: 'https://json-schema.org/draft/2020-12/schema',
  title: 'MONIT logical backup v2; reviewed row adapter fields7',
  ...object({
    formatVersion: { const: 2 }, contractVersion: { const: 2 }, createdAt: { type: 'string', format: 'date-time' },
    sourceLedger: object({ id, storageGeneration: id, revision: integer, timeZone: { const: 'Asia/Seoul' }, currency: { const: 'KRW' } }),
    collections: object(Object.fromEntries(finance.map(name => [name, array({ $ref: '#/$defs/' + name })]))),
    submissionsEvidence: array(object(witness)), operationFences: array(id), reminderPreferences: preferences, originals: array(original, 32),
    manifest: object({ ledgerId: id, recordCounts: object(counts), recordTotal: { type: 'integer', minimum: 0, maximum: 50000 },
      originals: array(object({ id, byteLength: integer, sha256: { type: 'string', pattern: '^[0-9a-f]{64}$' } }), 32),
      sourceMappings: { enum: ['NONE_PRESENT', 'NOT_RECORDED_BY_V1'] },
      submissionCoverage: { enum: ['RECORDED', 'NOT_RECORDED_BY_V1'] },
      excludes: { const: ['UI_DRAFT_PAYLOADS', 'PASSWORDS', 'DERIVED_CACHES', 'OS_PERMISSIONS', 'OS_ALARMS', 'NOTIFICATION_TOKENS', 'TEMP_FILES'] },
    }),
  }),
  $defs: defs,
};
fs.writeFileSync(path.join(root, 'core/data/src/main/resources/backup/backup-v2.schema.json'), JSON.stringify(schema, null, 2) + '\n');
process.stdout.write('Generated strict v2 from frozen reviewed fields7\n');
