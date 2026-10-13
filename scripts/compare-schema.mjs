import { readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const backend = process.argv[2] ?? path.resolve(root, '../King-Score');
const local = await readFile(path.join(backend, 'src/types/db.ts'), 'utf8');
const remote = await readFile(path.join(root, 'docs/evidence/schema.remote.ts'), 'utf8');
function rows(source) {
  const publicSchema = source.match(/^  public: \{([\s\S]*?)^  \}/m)?.[1];
  if (!publicSchema) throw new Error('Public schema not found');
  const result = {};
  for (const match of publicSchema.matchAll(/^      (\w+): \{\r?\n        Row: \{\r?\n([\s\S]*?)^        \}/gm)) {
    result[match[1]] = Object.fromEntries([...match[2].matchAll(/^          (\w+): (.+)$/gm)]
      .map(m => [m[1], m[2].trim()]));
  }
  if (Object.keys(result).length === 0) throw new Error('No rows parsed');
  return result;
}
const a = rows(local);
const b = rows(remote);
const differences = [];
for (const table of new Set([...Object.keys(a), ...Object.keys(b)])) {
  for (const field of new Set([...Object.keys(a[table] ?? {}), ...Object.keys(b[table] ?? {})])) {
    if (a[table]?.[field] !== b[table]?.[field]) differences.push({ table, field, local: a[table]?.[field] ?? null, remote: b[table]?.[field] ?? null });
  }
}
const report = {
  scope: 'public table Row fields and TS nullability only; not SQL types, RPCs, constraints or RLS',
  localSource: '../King-Score/src/types/db.ts', remoteSource: 'docs/evidence/schema.remote.ts',
  localSha256: createHash('sha256').update(local).digest('hex'),
  remoteSha256: createHash('sha256').update(remote).digest('hex'),
  tablesCompared: Object.keys(b).length, differences,
};
await writeFile(path.join(root, 'docs/evidence/schema-comparison.json'), JSON.stringify(report, null, 2) + '\n');
console.log(`${report.tablesCompared} public tables compared; ${differences.length} field/nullability differences.`);
if (differences.length > 0) process.exitCode = 1;
