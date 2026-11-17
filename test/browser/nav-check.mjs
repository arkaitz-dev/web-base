// What /wb/nav.js does in a real engine, run against `clojure -M:nav-probe [port]`:
//
//   PLAYWRIGHT_CORE=<path to playwright-core/index.mjs> node test/browser/nav-check.mjs <engine> <url>
//
// <engine> is `webkit` or `chromium` (CHROMIUM=<executable> for one Playwright did not
// install). Headless. Prints `{engine version checks {check {pass seen}}}` as JSON; its
// results are recorded in test/resources/nav-checks.edn, held to nav.js's bytes.
const { webkit, chromium } = await import(process.env.PLAYWRIGHT_CORE || 'playwright-core');

const [engine, base = 'http://localhost:3092'] = process.argv.slice(2);
const type = { webkit, chromium }[engine];
if (!type) { console.error('engine: webkit or chromium'); process.exit(2); }
const browser = await type.launch(process.env.CHROMIUM && engine === 'chromium' ? { executablePath: process.env.CHROMIUM } : {});
const ctx = await browser.newContext();
await ctx.addInitScript(`window.__csp=[];document.addEventListener("securitypolicyviolation",e=>window.__csp.push(e.violatedDirective));`);
const p = await ctx.newPage();
const errors = []; p.on('pageerror', e => errors.push(String(e)));
const checks = {};
const check = async (name, f, pass) => {
  try { const seen = await f(); checks[name] = { pass: pass(seen), seen }; }
  catch (e) { checks[name] = { pass: false, seen: 'ERROR ' + String(e).split('\n')[0] }; }
};
const marks = () => p.evaluate(() => Object.fromEntries([...document.querySelectorAll('a[id]')].map(a => [a.id, a.getAttribute('aria-current')])));
const at = (path) => p.waitForFunction(path => location.pathname === path, path).then(() => p.waitForTimeout(150));
const only = (m, id) => ['nav-a', 'nav-b', 'nav-c'].every(k => (m[k] === 'page') === (k === id));
const kept = (m, c) => m['nav-section'] === 'true' && m['nav-fragment'] === null && m['nav-query'] === null && m['nav-section-of-a'] === null && m['nav-elsewhere'] === null && m['main-c'] === null
  && (c === undefined || m['nav-c'] === c);

await p.goto(base + '/a');
await p.evaluate(() => { window.__loaded = 1; });
await check('on load: the page is marked, a host\'s section and false marks, a fragment, a query, a section of the page and another origin\'s same path are not', marks,
  m => only(m, 'nav-a') && kept(m, 'false'));
await check('a nav link swaps <main> and the mark follows, without a reload', async () => {
  await p.locator('#nav-b').click(); await at('/b');
  return { m: await marks(), same: await p.evaluate(() => window.__loaded === 1), focus: await p.evaluate(() => document.activeElement?.matches('main.wb-main')) };
}, s => only(s.m, 'nav-b') && kept(s.m, 'false') && s.same && s.focus);
await check('a link inside <main> moves the mark too, and a false mark becomes the page', async () => {
  await p.locator('#main-c').click(); await at('/c');
  return marks();
}, m => only(m, 'nav-c') && kept(m));
await check('Back marks the page it returns to', async () => {
  await p.goBack(); await at('/b'); return marks();
}, m => only(m, 'nav-b') && kept(m));
await check('Forward marks the page it goes to', async () => {
  await p.goForward(); await at('/c'); return marks();
}, m => only(m, 'nav-c') && kept(m));
checks['no CSP violation, no script error'] = await (async () => {
  const csp = await p.evaluate(() => window.__csp); return { pass: csp.length === 0 && errors.length === 0, seen: { csp, errors } };
})();
const version = browser.version();
await browser.close();
console.log(JSON.stringify({ engine, version, checks }, null, 1));
