/*
 * Verifies the script that PolicyBundle.kt actually produces.
 *
 * The Kotlin side wraps each file in try/catch and prepends a config assignment.
 * That wrapper is not exercised by run.mjs, so a stray `}` in a policy file could
 * pass the unit tests and still produce an unparseable bundle on device.
 *
 *   node tools/policy-test/bundle-check.mjs
 */
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const dir = path.resolve(here, '../../app/src/main/assets/policy');

// Must match PolicyBundle.FILES, in order.
const FILES = ['00_runtime.js', '10_feed.js', '20_dm.js', '30_dom.js', '90_health.js'];

// Read the version out of PolicyBundle.kt so this check can never drift from
// what ships.
const kotlinSource = fs.readFileSync(
  path.resolve(here, '../../app/src/main/java/app/enough/ig/policy/PolicyBundle.kt'),
  'utf8'
);
const versionMatch = /const val VERSION = "([^"]+)"/.exec(kotlinSource);
if (!versionMatch) throw new Error('could not read VERSION from PolicyBundle.kt');
const VERSION = versionMatch[1];
console.log(`  ..   policy version ${VERSION} (from PolicyBundle.kt)`);

const config = {
  policyVersion: VERSION,
  feedPageCap: 2,
  watermark: 1700000000,
  queueMaxAgeDays: 14,
  debug: false,
};

function assemble({ bridgeShim }) {
  let out = `(function(){try{window.__ENOUGH_CONFIG__=${JSON.stringify(config)};}catch(e){}});\n`;
  if (bridgeShim) {
    out +=
      '(function(){try{window.enoughBridge={postMessage:function(s){' +
      'window.EnoughNative&&window.EnoughNative.post(String(s));}};}catch(e){}});\n';
  }
  for (const name of FILES) {
    const src = fs.readFileSync(path.join(dir, name), 'utf8');
    out += `try{\n${src}\n}catch(e){try{console.error('[enough] ${name}',e)}catch(_){}}\n;\n`;
  }
  return out;
}

let failed = 0;

for (const bridgeShim of [false, true]) {
  const label = bridgeShim ? 'fallback bridge shim' : 'modern bridge';
  const src = assemble({ bridgeShim });
  try {
    new vm.Script(src, { filename: 'bundle.js' });
    console.log(`  ok   bundle parses (${label}), ${(src.length / 1024).toFixed(1)} KB`);
  } catch (e) {
    failed++;
    console.log(`  FAIL bundle does not parse (${label}): ${e.message}`);
  }
}

// Run it too, so a top-level throw is caught rather than only a syntax error.
try {
  const ctx = {
    console, JSON, Math, Object, Array, String, Number, Boolean, Set, Map, Date,
    Promise, Error, Response, URL, URLSearchParams,
    setTimeout: () => 0, clearTimeout: () => {},
    location: { pathname: '/' },
    document: {
      head: null, documentElement: null, visibilityState: 'visible',
      querySelector: () => null,
      createElement: () => ({ setAttribute() {}, set textContent(v) {}, appendChild() {} }),
      addEventListener() {},
    },
    enoughBridge: { postMessage() {} },
  };
  ctx.window = ctx;
  vm.createContext(ctx);
  vm.runInContext(assemble({ bridgeShim: false }), ctx, { filename: 'bundle.js' });

  if (ctx.window.__ENOUGH__ && typeof ctx.window.__ENOUGH__.processFeed === 'function') {
    console.log('  ok   bundle executes and exposes the runtime');
  } else {
    failed++;
    console.log('  FAIL runtime missing after execution');
  }
} catch (e) {
  failed++;
  console.log(`  FAIL bundle threw at load: ${e.message}`);
}

console.log(failed ? '\nbundle check failed\n' : '\nbundle check passed\n');
process.exit(failed ? 1 : 0);
