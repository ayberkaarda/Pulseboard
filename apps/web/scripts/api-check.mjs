// Fails when the checked-in API types differ from a fresh run of the generator.
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(fileURLToPath(new URL('.', import.meta.url)), '..');
const contract = resolve(root, '../../contracts/api-core.openapi.yaml');
const committed = resolve(root, 'src/app/api/generated/schema.d.ts');
const cli = resolve(root, 'node_modules/openapi-typescript/bin/cli.js');

const dir = mkdtempSync(join(tmpdir(), 'pb-api-'));
try {
  const fresh = join(dir, 'schema.d.ts');
  execFileSync(process.execPath, [cli, contract, '-o', fresh], { stdio: 'pipe' });
  const normalize = (file) => readFileSync(file, 'utf8').replace(/\r\n/g, '\n');
  if (normalize(fresh) !== normalize(committed)) {
    console.error(
      'src/app/api/generated/schema.d.ts is out of date with contracts/api-core.openapi.yaml.\n' +
        'Run "npm run api:generate" and commit the result.',
    );
    process.exit(1);
  }
  console.log('API types match the contract.');
} finally {
  rmSync(dir, { recursive: true, force: true });
}
