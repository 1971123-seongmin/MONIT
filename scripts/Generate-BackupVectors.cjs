// Synthetic, fixed interoperability fixtures. Never use these salt/nonces for real backups.
// Node's OpenSSL PBKDF2 receives explicit UTF-8 bytes; production Android uses PBEKeySpec/JCA.
const { pbkdf2Sync, createCipheriv } = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const payload = Buffer.from('{"synthetic":"합성🔐"}', 'utf8');
const vectors = ['합성비밀번호백업확인용1234', 'cafe\u0301-backup-test-2026', '🔐MONIT-합성-password-2026'].map(password => {
  const salt = Buffer.from(Array.from({ length: 16 }, (_, i) => i));
  const nonce = Buffer.from(Array.from({ length: 12 }, (_, i) => i + 16));
  const key = pbkdf2Sync(Buffer.from(password, 'utf8'), salt, 600000, 32, 'sha256');
  const header = Buffer.alloc(52);
  header.write('MONITBK1', 'ascii');
  header.writeUInt16BE(1, 8); header.writeUInt16BE(1, 10); header.writeUInt32BE(600000, 12);
  salt.copy(header, 16); nonce.copy(header, 32); header.writeBigUInt64BE(BigInt(payload.length + 16), 44);
  const cipher = createCipheriv('aes-256-gcm', key, nonce);
  cipher.setAAD(header);
  const sealed = Buffer.concat([cipher.update(payload), cipher.final(), cipher.getAuthTag()]);
  return { password, saltHex: salt.toString('hex'), nonceHex: nonce.toString('hex'), keyHex: key.toString('hex'), payloadHex: payload.toString('hex'), fileHex: Buffer.concat([header, sealed]).toString('hex') };
});
const target = path.join(__dirname, '../core/data/src/test/resources/backup/crypto-vectors.json');
fs.writeFileSync(target, JSON.stringify({ generator: 'Node.js/OpenSSL PBKDF2 UTF-8 and AES-256-GCM; synthetic fixed inputs', vectors }, null, 2) + '\n');
process.stdout.write(`Generated ${vectors.length} synthetic vectors\n`);
