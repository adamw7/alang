#!/usr/bin/env node
// Regenerates src/builtins.json from the toolchain's own table, src/main/java/alang/ast/Builtin.java,
// so hovers and completions describe exactly the builtins the checker knows.
//   node scripts/gen-builtins.js           write the file
//   node scripts/gen-builtins.js --check   exit 1 if it is out of date
'use strict';
const fs = require('fs');
const path = require('path');

const javaFile = path.resolve(__dirname, '../../../src/main/java/alang/ast/Builtin.java');
const outFile = path.resolve(__dirname, '../src/builtins.json');

function javaString(lit) {
  return JSON.parse(lit.replace(/\\'/g, "'"));
}

const STR = '"(?:[^"\\\\]|\\\\.)*"';
const PIECE = `(?:${STR}|(?:Builtin\\.)?[A-Z][A-Z_0-9]*)`;

// The text of a Java string expression: literals and named String constants joined with +.
function concat(expr, constants) {
  return expr.match(new RegExp(PIECE, 'g')).map((p) => {
    if (p.startsWith('"')) return javaString(p);
    const name = p.replace(/^Builtin\./, '');
    if (!(name in constants)) throw new Error(`gen-builtins: unknown constant ${p}`);
    return constants[name];
  }).join('');
}

function extract(source) {
  const constants = {};
  const constant = new RegExp(`static final String ([A-Z][A-Z_0-9]*)\\s*=\\s*((?:${STR}\\s*\\+?\\s*)+);`, 'g');
  for (const c of source.matchAll(constant)) constants[c[1]] = concat(c[2], constants);
  const body = source.slice(source.indexOf('public enum Builtin {'), source.indexOf('public enum Group {'));
  const entry = new RegExp(`\\n\\s+[A-Z][A-Z_0-9]*\\(Group\\.([A-Z]+),\\s*(true|false),\\s*(${STR}),\\s*(${STR}),\\s*((?:${PIECE}\\s*\\+?\\s*)+)\\)`, 'g');
  const builtins = [];
  let m;
  while ((m = entry.exec(body)) !== null) {
    builtins.push({
      name: javaString(m[3]),
      group: m[1].toLowerCase(),
      signature: javaString(m[4]),
      description: concat(m[5], constants),
    });
  }
  return builtins;
}

const source = fs.readFileSync(javaFile, 'utf8');
const builtins = extract(source);
const declared = (source.match(/\n    [A-Z][A-Z_0-9]*\(Group\./g) || []).length;
if (builtins.length !== declared) {
  console.error(`gen-builtins: read ${builtins.length} of the ${declared} builtins in ${javaFile}; has its format changed?`);
  process.exit(1);
}
const text = JSON.stringify(builtins, null, 2) + '\n';
if (process.argv.includes('--check')) {
  const current = fs.existsSync(outFile) ? fs.readFileSync(outFile, 'utf8') : '';
  if (current !== text) {
    console.error('src/builtins.json is out of date: run `node scripts/gen-builtins.js`');
    process.exit(1);
  }
  console.log(`src/builtins.json is up to date (${builtins.length} builtins)`);
} else {
  fs.writeFileSync(outFile, text);
  console.log(`wrote ${outFile} (${builtins.length} builtins)`);
}
