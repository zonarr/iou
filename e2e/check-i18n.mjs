// Checks the translations: English and Thai must have exactly the same keys, and every
// key the screens use must exist. Run with:  node e2e/check-i18n.mjs
import { readFileSync, readdirSync } from 'node:fs';

const dir = new URL('../src/main/resources/static/js/', import.meta.url);
const source = readFileSync(new URL('i18n.js', dir), 'utf8');

function keysOf(name) {
  const block = source.slice(source.indexOf(`const ${name} = {`), source.indexOf('\n};', source.indexOf(`const ${name} = {`)));
  return new Set([...block.matchAll(/^\s*'([^']+)':/gm)].map((m) => m[1]));
}
const en = keysOf('en');
const th = keysOf('th');
const problems = [];
for (const key of en) if (!th.has(key)) problems.push(`missing in Thai: ${key}`);
for (const key of th) if (!en.has(key)) problems.push(`missing in English: ${key}`);

const used = new Set();
const prefixes = new Set();
for (const file of readdirSync(dir).filter((f) => f.endsWith('.js') && f !== 'i18n.js')) {
  const text = readFileSync(new URL(file, dir), 'utf8');
  for (const m of text.matchAll(/\bt\(\s*'([^']+)'/g)) used.add(m[1]);
  for (const m of text.matchAll(/\bt\(\s*`([^`$]+)\$\{/g)) prefixes.add(m[1]);   // t(`status.${x}`)
  for (const m of text.matchAll(/fail\('([^']+)'\)/g)) used.add(`err.${m[1]}`);
}
for (const key of used) if (!en.has(key)) problems.push(`used but not defined: ${key}`);
for (const prefix of prefixes) {
  if (![...en].some((key) => key.startsWith(prefix))) problems.push(`no keys start with: ${prefix}`);
}

if (problems.length) {
  console.error(problems.join('\n'));
  process.exit(1);
}
console.log(`i18n ok: ${en.size} keys in both languages, ${used.size} used directly.`);
