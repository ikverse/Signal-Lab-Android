// Tests for the Learn page shell (app/src/main/assets/learn/page.html), run by ChartPageTest with `node --test`.
//
// The page's own script is loaded as it is, against stand-ins for the Markdown reader and the diagram library and a small stand-in for the
// page's elements. What is tested is when the page asks for the 3.3 MB diagram library (only for a page that has a diagram), that text shows
// before a diagram does, and what happens when a diagram cannot be had or drawn.

import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';

// A test that waits for something that never comes (a promise nothing resolves) fails after five seconds instead of hanging the build.
const t = (name, fn) => test(name, { timeout: 5000 }, fn);

// LEARN_PAGE points the tests at another copy of the page, to check that a deliberately broken one is caught.
const html = readFileSync(process.env.LEARN_PAGE || new URL('../../main/assets/learn/page.html', import.meta.url), 'utf8');
const pageScript = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map((m) => m[1]).join('\n');

class El {
  constructor(tag) { this.tag = tag; this.className = ''; this.textContent = ''; this.children = []; this.parentElement = null; }
  appendChild(c) { c.parentElement = this; this.children.push(c); return c; }
  replaceWith(other) {
    const p = this.parentElement;
    p.children[p.children.indexOf(this)] = other;
    other.parentElement = p;
    this.parentElement = null;
  }
  // The only HTML the stand-in reader produces: paragraphs, and the fenced Mermaid blocks as <pre><code class="language-mermaid">.
  set innerHTML(markup) {
    this.children = [];
    for (const part of markup.split('\n').filter(Boolean)) {
      const m = part.match(/^<pre><code class="language-mermaid">(.*)<\/code><\/pre>$/);
      if (m) {
        const pre = new El('pre'); const code = new El('code');
        code.className = 'language-mermaid'; code.textContent = m[1];
        pre.appendChild(code); this.appendChild(pre);
      } else {
        const p = new El('p'); p.textContent = part; this.appendChild(p);
      }
    }
  }
  descendants() { return this.children.flatMap((c) => [c, ...c.descendants()]); }
  querySelectorAll(sel) {
    if (sel === 'pre > code.language-mermaid') return this.descendants().filter((e) => e.tag === 'code' && e.className === 'language-mermaid' && e.parentElement.tag === 'pre');
    if (sel === 'svg') return this.descendants().filter((e) => e.tag === 'svg');
    throw new Error(`the stand-in does not know the selector ${sel}`);
  }
  querySelector(sel) { return this.querySelectorAll(sel)[0] || null; }
}

function load({ width } = {}) {
  const host = new El('div');
  const head = new El('head');
  const scripts = [];
  const scrolls = [];
  const inits = [];
  const runs = [];
  const state = { runFails: false };
  head.appendChild = (s) => { scripts.push(s); return s; };

  const ctx = {
    document: { getElementById: () => host, createElement: (tag) => new El(tag), head },
    marked: { parse: (md) => md.split('\n').map((l) => (l === '```mermaid' || l === '```' ? null : l)).filter((l) => l !== null)
      .map((l, i, arr) => l).join('\n') },
    console,
    scrollTo: (x, y) => scrolls.push([x, y]),
    innerWidth: width,
  };
  // The stand-in reader: a "```mermaid" fence around a line becomes the block the real reader produces.
  ctx.marked.parse = (md) => {
    const out = [];
    const lines = md.split('\n');
    for (let i = 0; i < lines.length; i++) {
      if (lines[i] === '```mermaid') { out.push(`<pre><code class="language-mermaid">${lines[i + 1]}</code></pre>`); i += 2; } else out.push(lines[i]);
    }
    return out.join('\n');
  };
  ctx.window = ctx;
  vm.createContext(ctx);
  vm.runInContext(pageScript, ctx);

  return {
    host, scripts, scrolls, inits, runs, state, render: ctx.render,
    // The library arrives (or fails to) for the n-th script the page asked for.
    arrived(n) {
      ctx.mermaid = {
        initialize: (cfg) => inits.push(JSON.parse(JSON.stringify(cfg))),
        async run({ nodes }) {
          runs.push(nodes);
          if (state.runFails) throw new Error('this diagram is not valid');
          nodes.forEach((node) => node.appendChild(new El('svg')));
        },
      };
      scripts[n].onload();
    },
    failed(n) { scripts[n].onerror(); },
  };
}

const plain = '# A page\nWith some text.';
const withDiagram = 'Some text.\n```mermaid\ngraph LR\n```\nMore text.';

t('the diagram library is not part of the page: only the small Markdown reader is', () => {
  assert.ok(/<script src="marked\.min\.js"><\/script>/.test(html), 'the Markdown reader loads with the page');
  assert.ok(!/<script[^>]*mermaid/i.test(html), 'the 3.3 MB diagram library does not');
});

t('a page without a diagram never asks for the diagram library', async () => {
  const env = load();
  const svgs = await env.render(plain);
  assert.equal(env.scripts.length, 0);
  assert.equal(svgs, 0);
  assert.deepEqual(env.host.children.map((c) => c.textContent), ['# A page', 'With some text.']);
  assert.equal(env.inits.length, 0);
});

t('a page with a diagram shows its text at once, scrolled to the top, and asks for the library only then', async () => {
  const env = load();
  const pending = env.render(withDiagram);
  // Everything up to the first wait has happened: the text is there, the diagram's place is held, the page is at the top.
  assert.deepEqual(env.host.children.map((c) => c.className || c.textContent), ['Some text.', 'mermaid', 'More text.']);
  assert.equal(env.host.children[1].textContent, 'graph LR');
  assert.deepEqual(env.scrolls, [[0, 0]]);
  assert.deepEqual(env.scripts.map((s) => s.src), ['mermaid.min.js']);
  assert.equal(env.runs.length, 0, 'nothing is drawn before the library is there');
  env.arrived(0);
  const svgs = await pending;
  assert.equal(svgs, 1);
  assert.equal(env.inits.length, 1);
  assert.equal(env.inits[0].startOnLoad, false);
  assert.equal(env.inits[0].securityLevel, 'strict');
  assert.equal(env.runs.length, 1);
  assert.equal(env.runs[0][0].className, 'mermaid');
});

t('the library is loaded and set up once, however many pages have diagrams', async () => {
  const env = load();
  const first = env.render(withDiagram);
  env.arrived(0);
  await first;
  await env.render(withDiagram);
  await env.render(plain);
  await env.render(withDiagram);
  assert.equal(env.scripts.length, 1);
  assert.equal(env.inits.length, 1);
  assert.equal(env.runs.length, 3);
});

t('a library that cannot be loaded leaves the text and says the diagram could not be drawn, and a later page tries again', async () => {
  const env = load();
  const first = env.render(withDiagram);
  env.failed(0);
  assert.equal(await first, 0);
  assert.equal(env.host.children[1].className, 'failed');
  assert.equal(env.host.children[1].textContent, 'This diagram could not be drawn.');
  assert.equal(env.host.children[0].textContent, 'Some text.', 'the text is still there');
  const second = env.render(withDiagram);
  assert.equal(env.scripts.length, 2, 'it asks for the library again rather than staying broken');
  env.arrived(1);
  assert.equal(await second, 1);
});

t('a diagram that will not draw says so and shows nothing else in its place', async () => {
  const env = load();
  const pending = env.render(withDiagram);
  env.state.runFails = true;
  env.arrived(0);
  assert.equal(await pending, 0);
  assert.equal(env.host.children[1].className, 'failed');
  assert.equal(env.host.children[1].textContent, 'This diagram could not be drawn.');
});

t('a page opened while the last one waits for its diagram wins, and the late diagram is not drawn over it', async () => {
  const env = load();
  const slow = env.render(withDiagram);
  await env.render(plain);
  env.arrived(0);
  assert.equal(await slow, 0);
  assert.equal(env.runs.length, 0, 'the diagram of the page that was left is not drawn');
  assert.deepEqual(env.host.children.map((c) => c.textContent), ['# A page', 'With some text.']);
});

t('on a phone a flow drawn left to right is drawn top to bottom instead, and on a wide screen it is left alone', async () => {
  const phone = load({ width: 400 });
  const pending = phone.render(withDiagram);
  assert.equal(phone.host.children[1].textContent, 'graph TD');
  phone.arrived(0);
  await pending;
  const wide = load({ width: 900 });
  const later = wide.render(withDiagram);
  assert.equal(wide.host.children[1].textContent, 'graph LR');
  wide.arrived(0);
  await later;
  const flow = load({ width: 400 });
  const third = flow.render('```mermaid\nflowchart LR; A-->B\n```');
  assert.equal(flow.host.children[0].textContent, 'flowchart TD; A-->B');
  flow.arrived(0);
  await third;
});
