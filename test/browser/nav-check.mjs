// What /wb/nav.js does in a real engine, run against `clojure -M:nav-probe [port]`:
//
//   PLAYWRIGHT_CORE=<path to playwright-core/index.mjs> node test/browser/nav-check.mjs <engine> <url>
//
// <engine> is `webkit` or `chromium` (CHROMIUM=<executable> for one Playwright did not
// install). Headless; Chromium keeps its scrollbars (no --hide-scrollbars), so a page that
// overflows has one that takes width, and the header's :aligned inset is checked where the
// body's width and the viewport's differ. Prints `{engine version checks {check {pass seen}}}` as JSON; its
// results are recorded in test/resources/nav-checks.edn, held to nav.js's bytes.
const { webkit, chromium } = await import(process.env.PLAYWRIGHT_CORE || 'playwright-core');

const [engine, base = 'http://localhost:3092'] = process.argv.slice(2);
const type = { webkit, chromium }[engine];
if (!type) { console.error('engine: webkit or chromium'); process.exit(2); }
const browser = await type.launch(engine === 'chromium'
  ? { ignoreDefaultArgs: ['--hide-scrollbars'], ...(process.env.CHROMIUM ? { executablePath: process.env.CHROMIUM } : {}) } : {});
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

// The shell's header at each :header-width, measured in a page of its own at a set width.
// 48rem = 768px and --wb-gap = 16px, so at 1470 the column is 351..1119 and its text 367..1103.
const box = async (path, width) => {
  const c = await browser.newContext({ viewport: { width, height: 700 } });
  await c.addInitScript(`window.__csp=[];document.addEventListener("securitypolicyviolation",e=>window.__csp.push(e.violatedDirective));`);
  const q = await c.newPage(); await q.goto(base + path);
  const seen = await q.evaluate(() => {
    const R = (s) => { const r = document.querySelector(s).getBoundingClientRect(); return [r.left, r.right]; };
    const f = document.querySelector('#hw-fixed').getBoundingClientRect();
    return { cw: document.documentElement.clientWidth, iw: innerWidth, header: R('header.wb-header'), child: R('#hw-child'),
      bleed: R('#hw-bleed'), text: R('#hw-text'), fixed: [f.right, f.bottom], ih: innerHeight, csp: window.__csp };
  });
  if (path.endsWith('/tall')) seen.stuck = await q.evaluate(() => { scrollTo(0, 3000); return [scrollY, document.querySelector('header.wb-header').getBoundingClientRect().top]; });
  await c.close(); return seen;
};
const eq = (a, b) => a[0] === b[0] && a[1] === b[1];
const at1470 = (s) => s.cw === 1470 && s.iw === 1470 && s.csp.length === 0;
await check('header width, default at 1470: the header spans the window, its content 16px in', () => box('/hw/default', 1470),
  s => at1470(s) && eq(s.header, [0, 1470]) && eq(s.child, [16, 1454]) && eq(s.bleed, [0, 1470]));
await check('header width, aligned at 1470: the header spans the window, its content starts and ends with main\'s text', () => box('/hw/aligned', 1470),
  s => at1470(s) && eq(s.header, [0, 1470]) && eq(s.child, [367, 1103]) && eq(s.text, [367, 1103]) && eq(s.bleed, [351, 1119]));
// A host's own --wb-gap (24px) and --wb-measure (40rem = 640px): the column is 415..1055, its text 439..1031.
await check('header width, aligned under a host\'s own gap and measure: its content follows them', () => box('/hw/aligned/themed', 1470),
  s => at1470(s) && eq(s.header, [0, 1470]) && eq(s.child, [439, 1031]) && eq(s.text, [439, 1031]) && eq(s.bleed, [415, 1055]));
await check('header width, measure at 1470: the header is the centred 48rem column', () => box('/hw/measure', 1470),
  s => at1470(s) && eq(s.header, [351, 1119]) && eq(s.child, [367, 1103]) && eq(s.text, [367, 1103]) && eq(s.bleed, [351, 1119]));
await check('header width at 360: every mode is the window with a 16px inset, never less', async () => {
  const out = {}; for (const m of ['default', 'aligned', 'measure']) out[m] = await box('/hw/' + m, 360); return out;
}, o => Object.values(o).every(s => s.cw === 360 && s.csp.length === 0 && eq(s.header, [0, 360]) && eq(s.child, [16, 344]) && eq(s.text, [16, 344]) && eq(s.bleed, [0, 360])));
await check('header width: a fixed element still stands against the viewport, in every mode', async () => {
  const out = {}; for (const m of ['default', 'aligned', 'measure']) out[m] = await box('/hw/' + m, 1470); return out;
}, o => Object.values(o).every(s => s.fixed[0] === s.cw && s.fixed[1] === s.ih));

// /hw.css styles the root's scrollbar 15px wide, which both engines then lay out beside the page.
await check('header width, beside a 15px scrollbar: the inset follows the body\'s width, not the viewport\'s, and a sticky header still sticks', async () => {
  const out = {}; for (const m of ['default', 'aligned', 'measure']) out[m] = await box('/hw/' + m + '/tall', 1470); return out;
}, o => Object.values(o).every(s => s.iw === 1470 && s.cw === 1455 && s.csp.length === 0 && eq(s.stuck, [3000, 0]))
  && eq(o.default.header, [0, 1455]) && eq(o.default.child, [16, 1439]) && eq(o.default.bleed, [0, 1455])
  && eq(o.aligned.header, [0, 1455]) && eq(o.aligned.child, [359.5, 1095.5]) && eq(o.aligned.text, [359.5, 1095.5]) && eq(o.aligned.bleed, [343.5, 1111.5])
  && eq(o.measure.header, [343.5, 1111.5]) && eq(o.measure.child, [359.5, 1095.5]) && eq(o.measure.bleed, [343.5, 1111.5]));

// The user's default font size. Chromium takes it through CDP (Page.setFontSizes, measured to
// take whether called before or after the load); WebKit has no such knob, so there the check
// holds the body at the engine's own default only, and the user-default half is Chromium's.
await check('type size: the body and a paragraph in <main> are the user\'s default font size (20 where the engine lets it be set, else its 16)', async () => {
  const c = await browser.newContext();
  await c.addInitScript(`window.__csp=[];document.addEventListener("securitypolicyviolation",e=>window.__csp.push(e.violatedDirective));`);
  const q = await c.newPage();
  const knob = engine === 'chromium' ? 20 : null;
  if (knob) await (await c.newCDPSession(q)).send('Page.setFontSizes', { fontSizes: { standard: knob } });
  await q.goto(base + '/a');
  const seen = await q.evaluate(() => {
    // On <html>, so no stylesheet of the page sizes them: `medium` is the default size itself,
    // and 16px stays 16px unless the knob scaled everything (a zoom, a minimum size).
    const span = (v) => { const e = document.createElement('span'); e.style.fontSize = v; document.documentElement.append(e); const f = getComputedStyle(e).fontSize; e.remove(); return f; };
    return { medium: span('medium'), fixed: span('16px'), root: getComputedStyle(document.documentElement).fontSize,
      body: getComputedStyle(document.body).fontSize, p: getComputedStyle(document.querySelector('main #label')).fontSize, csp: window.__csp };
  });
  await c.close(); return { knob, ...seen };
}, s => {
  const want = s.knob ? s.knob + 'px' : '16px';
  return s.medium === want && s.fixed === '16px' && s.root === want && s.body === want && s.p === want && s.csp.length === 0;
});
checks['no CSP violation, no script error'] = await (async () => {
  const csp = await p.evaluate(() => window.__csp); return { pass: csp.length === 0 && errors.length === 0, seen: { csp, errors } };
})();
const version = browser.version();
await browser.close();
console.log(JSON.stringify({ engine, version, checks }, null, 1));
