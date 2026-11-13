// Logical v3 extends the frozen v2 adapter only with the reviewed Room9 import fields.
const fs = require('node:fs');
const path = require('node:path');
const root = path.join(__dirname, '..');
const directory = path.join(root, 'core/data/src/main/resources/backup');
const schema = JSON.parse(fs.readFileSync(path.join(directory, 'backup-v2.schema.json'), 'utf8'));
const database = JSON.parse(fs.readFileSync(path.join(root, 'core/data/schemas/com.monit.core.data.LedgerDatabase/9.json'), 'utf8')).database;
const tables = ['import_batches', 'import_rows', 'import_source_mappings', 'import_child_commands', 'import_source_fences'];
const coverage = { enum: ['RECORDED', 'NOT_RECORDED_BY_V1', 'NOT_RECORDED_BY_V2'] };
const counts = schema.properties.manifest.properties.recordCounts;
const imports = { coverage };
schema.title = 'MONIT logical backup v3; atomic import evidence';
schema.properties.formatVersion.const = 3;
schema.properties.contractVersion.const = 3;
schema.required.push('imports');
for (const table of tables) {
  const entity = database.entities.find(value => value.tableName === table);
  if (!entity) throw Error('Missing reviewed import table: ' + table);
  const properties = {};
  for (const field of entity.fields) {
    const name = field.columnName;
    let value = { type: 'string', maxLength: name.endsWith('Json') ? 8388608 : 16384 };
    if (field.affinity === 'INTEGER') value = { type: 'string', pattern: '^(0|-[1-9][0-9]*|[1-9][0-9]*)$', maxLength: 20 };
    if (name === 'position') value = { type: 'integer', minimum: 0, maximum: 9999 };
    if (['acknowledged', 'evidenceOnly'].includes(name)) value = { type: 'boolean' };
    if (name.endsWith('Sha256') || ['identity', 'fingerprint'].includes(name)) value = { type: 'string', pattern: '^[0-9a-f]{64}$' };
    properties[name] = field.notNull ? value : { anyOf: [value, { type: 'null' }] };
  }
  if (table === 'import_batches') properties.status = { enum: ['REQUESTED', 'COMMITTED'] };
  if (table === 'import_rows') properties.decision = { enum: ['CANDIDATE', 'APPLIED', 'EXISTS', 'HELD', 'EXCLUDED'] };
  schema.$defs[table] = { type: 'object', additionalProperties: false, required: Object.keys(properties), properties, 'x-primaryKey': entity.primaryKey.columnNames, 'x-foreignKeys': entity.foreignKeys };
  imports[table] = { type: 'array', maxItems: 50000, items: { $ref: '#/$defs/' + table } };
  counts.required.push(table);
  counts.properties[table] = { type: 'integer', minimum: 0, maximum: 50000 };
}
schema.properties.imports = { type: 'object', additionalProperties: false, required: Object.keys(imports), properties: imports };
counts.required.push('originals');
counts.properties.originals = { type: 'integer', minimum: 0, maximum: 32 };
schema.properties.manifest.properties.sourceMappings = coverage;
fs.writeFileSync(path.join(directory, 'backup-v3.schema.json'), JSON.stringify(schema, null, 2) + '\n');
