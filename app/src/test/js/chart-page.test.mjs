// Tests for the chart page (app/src/main/assets/chart/chart.html), run by ChartPageTest with `node --test`.
//
// The page's own script is loaded as it is, against a stand-in for the chart library that records every call and keeps overlays and
// indicators the way the library reports them, and against a small stand-in for the page's elements. What is tested is what the page
// does with the library (when it resizes it, what it creates and removes, what it reports to the app), not how the library draws.

import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';

// A test that waits for something that never comes (a promise nothing resolves) fails after five seconds instead of hanging the build.
const t = (name, fn) => test(name, { timeout: 5000 }, fn);

// CHART_PAGE points the tests at another copy of the page, to check that a deliberately broken one is caught.
const html = readFileSync(process.env.CHART_PAGE || new URL('../../main/assets/chart/chart.html', import.meta.url), 'utf8');
// The page's own script is the one without a src; the library is the one with.
const pageScript = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map((m) => m[1]).join('\n');

class El {
  constructor(tag) { this.tag = tag; this.style = {}; this.className = ''; this.textContent = ''; this.children = []; this.listeners = {}; }
  appendChild(c) { this.children.push(c); return c; }
  addEventListener(type, fn) { (this.listeners[type] ||= []).push(fn); }
  click() { for (const fn of this.listeners.click || []) fn({}); }
  set innerHTML(_) { this.children = []; }
  get innerHTML() { return ''; }
}

/**
 * One row of a menu as the person reads it: its tick box ("[x]" ticked, "[ ]" not, nothing for a row without one), then its words. The box
 * is drawn, not written, so whether it is ticked is read from the row being marked on.
 */
const rowText = (row) => {
  const box = row.children.find((c) => c.className === 'box');
  const words = row.children.filter((c) => c.className !== 'box').map((c) => c.text ?? c.textContent).join('');
  return `${box ? (row.className.split(' ').includes('on') ? '[x]' : '[ ]') : ''}|${words}`;
};

function load({ observer = true } = {}) {
  const calls = [];
  const overlays = new Map();
  const els = new Map();
  const observers = [];
  const windowListeners = [];
  const reported = { indicators: [], drawings: [], menu: [] };
  let overlaySeq = 0, paneSeq = 0, dataList = [];

  const registered = {};
  const chart = {
    resizes: 0, failResize: false, styles: null,
    setStyles: (s) => { chart.styles = s; },
    resize() { chart.resizes++; if (chart.failResize) throw new Error('resize failed'); },
    applyNewData(rows) { dataList = rows; calls.push(['applyNewData', rows.length]); },
    getDataList: () => dataList,
    createIndicator(value, isStack, paneOptions) {
      const pane = isStack ? paneOptions.id : `pane${++paneSeq}`;
      // Copied through JSON: the page's own objects come from another realm, which strict deep equality would call different.
      calls.push(['createIndicator', value.name, !!isStack, JSON.parse(JSON.stringify(paneOptions))]);
      return pane;
    },
    removeIndicator(paneId, name) { calls.push(['removeIndicator', paneId, name]); },
    createOverlay(o) {
      const id = `o${++overlaySeq}`;
      overlays.set(id, { ...o, id, points: o.points ? o.points.map((p) => ({ ...p })) : [] });
      calls.push(['createOverlay', o.name, o.groupId, id]);
      return id;
    },
    getOverlayById: (id) => overlays.get(id) || null,
    removeOverlay(arg) {
      calls.push(['removeOverlay', typeof arg === 'string' ? arg : JSON.stringify(arg)]);
      if (typeof arg === 'string') overlays.delete(arg);
      else for (const [id, o] of overlays) if (o.groupId === arg.groupId) overlays.delete(id);
    },
    scrollToRealTime() { calls.push(['scrollToRealTime']); },
    scrollByDistance() {},
  };

  const document = {
    getElementById(id) { if (!els.has(id)) els.set(id, new El('div')); return els.get(id); },
    createElement: (tag) => new El(tag),
    createTextNode: (text) => ({ text }),
  };
  class ResizeObserver {
    constructor(cb) { this.cb = cb; this.target = null; observers.push(this); }
    observe(el) { this.target = el; }
  }
  const ctx = {
    document, performance: { now: () => 0 }, requestAnimationFrame: () => {}, console,
    klinecharts: { init: () => chart, registerIndicator: (d) => { registered[d.name] = d; }, registerOverlay: (o) => { registered[o.name] = o; } },
    addEventListener: (type, fn) => windowListeners.push([type, fn]),
    Android: {
      indicatorsChanged: (csv) => reported.indicators.push(csv),
      drawingsChanged: (json) => reported.drawings.push(JSON.parse(json)),
      menuChanged: (open) => reported.menu.push(open),
    },
  };
  if (observer) ctx.ResizeObserver = ResizeObserver;
  ctx.window = ctx;
  vm.createContext(ctx);
  vm.runInContext(pageScript, ctx);

  return {
    chart, calls, overlays, observers, reported, registered, lab: ctx.signalLab,
    el: (id) => document.getElementById(id),
    fire: (type) => windowListeners.filter(([t]) => t === type).forEach(([, fn]) => fn({})),
    inGroup: (g) => [...overlays.values()].filter((o) => o.groupId === g),
    since: (mark) => calls.slice(mark),
  };
}

const candles = [[1000, 1, 2, 0.5, 1.5, 10], [2000, 1.5, 2.5, 1, 2, 7]];
const segment = { name: 'segment', points: [{ timestamp: 1000, value: 1 }, { timestamp: 2000, value: 2 }] };
const show = (env, key, drawings = [], levels = []) => env.lab.setData({ key, candles, levels, drawings });

// --- size

t('the chart is told to resize whenever its box or the window changes', () => {
  const env = load();
  assert.equal(env.observers.length, 1);
  assert.equal(env.observers[0].target, env.el('chart'), 'it watches the box the chart lives in');
  const before = env.chart.resizes;
  env.observers[0].cb([]);
  assert.equal(env.chart.resizes, before + 1);
  env.fire('resize');
  assert.equal(env.chart.resizes, before + 2);
});

t('a resize that fails never takes the page down', () => {
  const env = load();
  env.chart.failResize = true;
  assert.doesNotThrow(() => env.observers[0].cb([]));
  assert.doesNotThrow(() => env.fire('resize'));
});

t('without ResizeObserver the window event still resizes the chart', () => {
  const env = load({ observer: false });
  const before = env.chart.resizes;
  env.fire('resize');
  assert.equal(env.chart.resizes, before + 1);
});

// --- indicators

t('volume alone is on until the app says otherwise, in a panel of its own', () => {
  const env = load();
  assert.deepEqual(env.calls.filter((c) => c[0] === 'createIndicator'), [['createIndicator', 'VOLBARS', false, { height: 52, minHeight: 40 }]]);
});

t('the app sets the indicators and the page adds and removes exactly what changed', () => {
  const env = load();
  let mark = env.calls.length;
  env.lab.setIndicators(['VOL', 'MA', 'RSI']);
  assert.deepEqual(env.since(mark), [
    ['createIndicator', 'MA', true, { id: 'candle_pane' }], // averages are drawn on the price
    ['createIndicator', 'RSI', false, { height: 80, minHeight: 40 }], // RSI gets its own panel
  ]);
  mark = env.calls.length;
  env.lab.setIndicators(['VOL', 'MA', 'RSI']);
  assert.deepEqual(env.since(mark), [], 'the same list again changes nothing');
  env.lab.setIndicators(['RSI']);
  assert.deepEqual(env.since(mark), [['removeIndicator', 'pane1', 'VOLBARS'], ['removeIndicator', 'candle_pane', 'MA']]);
  mark = env.calls.length;
  env.lab.setIndicators([]);
  assert.deepEqual(env.since(mark), [['removeIndicator', 'pane2', 'RSI']]);
  mark = env.calls.length;
  assert.doesNotThrow(() => env.lab.setIndicators(['NOT-ONE']));
  env.lab.setIndicators(null);
  assert.deepEqual(env.since(mark), [], 'an id the page does not know is left out');
});

t('all six indicators can be on at once and each is made the way it is meant to be', () => {
  const env = load();
  const mark = env.calls.length;
  env.lab.setIndicators(['VOL', 'MA', 'EMA', 'BOLL', 'RSI', 'MACD']);
  const made = env.since(mark).filter((c) => c[0] === 'createIndicator').map((c) => `${c[1]}:${c[2] ? 'price' : 'panel'}`);
  assert.deepEqual(made, ['MA:price', 'EMA:price', 'BOLL:price', 'RSI:panel', 'MACD:panel']);
});

t('the Indicators menu lists them with a tick for those on, ticking one adds it and tells the app', () => {
  const env = load();
  env.lab.openIndicators();
  const menu = env.el('menu');
  assert.equal(menu.style.display, 'block');
  assert.equal(env.el('shade').style.display, 'block');
  assert.deepEqual(menu.children.map(rowText), [
    '[x]|Volume', '[ ]|Moving averages (20, 50)', '[ ]|Exponential averages (20, 50)',
    '[ ]|Bollinger Bands (20, 2)', '[ ]|RSI (14)', '[ ]|MACD (12, 26, 9)',
  ]);
  const mark = env.calls.length;
  menu.children[1].click();
  assert.deepEqual(env.reported.indicators, ['VOL,MA']);
  assert.deepEqual(env.since(mark), [['createIndicator', 'MA', true, { id: 'candle_pane' }]]);
  assert.equal(env.el('menu').style.display, 'block', 'the menu stays open so several can be ticked in one go');
  assert.equal(rowText(env.el('menu').children[1]), '[x]|Moving averages (20, 50)');
  env.el('menu').children[0].click(); // Volume off
  assert.deepEqual(env.reported.indicators, ['VOL,MA', 'MA']);
  env.el('menu').children[1].click(); // MA off: none left
  assert.deepEqual(env.reported.indicators, ['VOL,MA', 'MA', '']);
});

t('the menu closes when its button is touched again or anywhere outside it', () => {
  const env = load();
  env.lab.openIndicators();
  env.lab.openIndicators();
  assert.equal(env.el('menu').style.display, 'none');
  assert.equal(env.el('shade').style.display, 'none');
  env.lab.openDraw();
  assert.equal(env.el('menu').style.display, 'block');
  env.el('shade').click();
  assert.equal(env.el('menu').style.display, 'none');
  env.lab.openIndicators();
  env.lab.openDraw(); // another button while one is open: switches
  assert.equal(rowText(env.el('menu').children[0]), '|Trend line');
});

t('the app is told when a menu opens and closes, and can close it itself (its Back button)', () => {
  const env = load();
  env.lab.openIndicators();
  env.lab.openDraw(); // switching menus keeps one open: nothing new to tell
  assert.deepEqual(env.reported.menu, ['true']);
  env.lab.closeMenu();
  assert.equal(env.el('menu').style.display, 'none');
  assert.equal(env.el('shade').style.display, 'none');
  assert.deepEqual(env.reported.menu, ['true', 'false']);
  env.lab.closeMenu(); // already closed: said once only
  assert.deepEqual(env.reported.menu, ['true', 'false']);
});

t('a menu left open is closed when another chart is shown', () => {
  const env = load();
  show(env, 'BTCUSDT|1h');
  env.lab.openIndicators();
  show(env, 'ETHUSDT|1h');
  assert.equal(env.el('menu').style.display, 'none');
  assert.deepEqual(env.reported.menu, ['true', 'false']);
});

t('indicators the app sets while the menu is open are shown in it', () => {
  const env = load();
  env.lab.openIndicators();
  env.lab.setIndicators(['VOL', 'RSI']);
  assert.equal(rowText(env.el('menu').children[4]), '[x]|RSI (14)');
});

// --- drawing

t('the Draw menu offers the five tools and clearing', () => {
  const env = load();
  env.lab.openDraw();
  assert.deepEqual(env.el('menu').children.map(rowText), [
    '|Trend line', '|Horizontal line', '|Ray', '|Parallel channel', '|Fibonacci retracement', '|Clear drawings',
  ]);
});

t('choosing a tool starts that drawing, shows what to tap, and finishing it reports the drawing', () => {
  const env = load();
  show(env, 'BTCUSDT|1h');
  env.lab.openDraw();
  env.el('menu').children[0].click();
  assert.equal(env.el('menu').style.display, 'none');
  assert.equal(env.el('hint').textContent, 'Tap the start, then the end');
  const [o] = env.inGroup('draw');
  assert.equal(o.name, 'segment');
  assert.deepEqual(env.reported.drawings, [], 'nothing is reported before it is finished');
  o.points = [{ timestamp: 1000, value: 1, dataIndex: 0 }, { timestamp: 2000, value: 2, dataIndex: 1 }];
  o.onDrawEnd();
  assert.equal(env.el('hint').textContent, '');
  assert.deepEqual(env.reported.drawings, [{ key: 'BTCUSDT|1h', drawings: [segment] }]);
});

t('each tool is the library drawing it is named for', () => {
  const env = load();
  const names = [];
  for (let i = 0; i < 5; i++) {
    env.lab.openDraw();
    env.el('menu').children[i].click();
    const [o] = env.inGroup('draw').slice(-1);
    names.push(o.name);
    o.points = [{ timestamp: 1, value: 1 }];
    o.onDrawEnd();
  }
  assert.deepEqual(names, ['segment', 'horizontalStraightLine', 'rayLine', 'parallelStraightLine', 'fibonacciLine']);
});

t('a tool picked and then given up for another leaves no stray drawing behind', () => {
  const env = load();
  show(env, 'BTCUSDT|1h');
  env.lab.openDraw();
  env.el('menu').children[0].click();
  const first = env.inGroup('draw')[0].id;
  env.lab.openDraw();
  env.el('menu').children[2].click();
  assert.ok(env.calls.some((c) => c[0] === 'removeOverlay' && c[1] === first), 'the first, unfinished, one was removed');
  assert.deepEqual(env.inGroup('draw').map((o) => o.name), ['rayLine']);
});

t('moving a finished drawing reports its new place', () => {
  const env = load();
  show(env, 'BTCUSDT|1h', [segment]);
  const [o] = env.inGroup('draw');
  o.points[0].value = 1.5;
  o.onPressedMoveEnd();
  assert.equal(env.reported.drawings.at(-1).drawings[0].points[0].value, 1.5);
});

t('clearing removes every drawing of the chart and reports that none are left', () => {
  const env = load();
  show(env, 'BTCUSDT|1h', [segment, { ...segment, name: 'rayLine' }]);
  assert.equal(env.inGroup('draw').length, 2);
  env.lab.openDraw();
  env.el('menu').children[5].click();
  assert.equal(env.inGroup('draw').length, 0);
  assert.deepEqual(env.reported.drawings.at(-1), { key: 'BTCUSDT|1h', drawings: [] });
});

// --- one set of drawings per coin and chart size

t('the drawings the app sends for a chart are drawn when that chart is first shown', () => {
  const env = load();
  show(env, 'BTCUSDT|1h', [segment]);
  const [o] = env.inGroup('draw');
  assert.equal(o.name, 'segment');
  assert.deepEqual(o.points, segment.points);
});

t('a refresh of the same chart keeps what the user drew and redraws only the levels', () => {
  const env = load();
  const levels = [{ kind: 'ENTRY', label: 'Entry', price: 1.5 }];
  show(env, 'BTCUSDT|1h', [segment], levels);
  const drawn = env.inGroup('draw')[0];
  const mark = env.calls.length;
  show(env, 'BTCUSDT|1h', [], levels); // the app has nothing newer for this chart: the page's own drawings stand
  assert.ok(!env.since(mark).some((c) => c[0] === 'removeOverlay' && c[1] === '{"groupId":"draw"}'));
  assert.equal(env.inGroup('draw')[0], drawn);
  assert.equal(env.inGroup('draw').length, 1);
  assert.ok(env.since(mark).some((c) => c[0] === 'removeOverlay' && c[1] === '{"groupId":"levels"}'), 'levels are redrawn each time');
  assert.equal(env.inGroup('levels').length, 1, 'one level, drawn as one line with its label');
});

t('another coin or chart size gets its own drawings, and the first one gets its back', () => {
  const env = load();
  show(env, 'BTCUSDT|1h', [segment]);
  show(env, 'ETHUSDT|1h', []);
  assert.equal(env.inGroup('draw').length, 0, "the other coin does not show BTC's line");
  show(env, 'ETHUSDT|4h', [{ ...segment, name: 'rayLine' }]);
  assert.deepEqual(env.inGroup('draw').map((o) => o.name), ['rayLine']);
  show(env, 'BTCUSDT|1h', [segment]); // the app keeps them and sends them again
  assert.deepEqual(env.inGroup('draw').map((o) => o.name), ['segment']);
});

t('changing chart abandons a half-drawn tool and clears its hint', () => {
  const env = load();
  show(env, 'BTCUSDT|1h');
  env.lab.openDraw();
  env.el('menu').children[0].click();
  assert.equal(env.el('hint').textContent, 'Tap the start, then the end');
  show(env, 'ETHUSDT|1h');
  assert.equal(env.el('hint').textContent, '');
  assert.equal(env.inGroup('draw').length, 0);
});

t('a drawing reported after changing chart is reported under the new chart', () => {
  const env = load();
  show(env, 'BTCUSDT|1h', [segment]);
  show(env, 'ETHUSDT|1h', [segment]);
  env.inGroup('draw')[0].onPressedMoveEnd();
  assert.equal(env.reported.drawings.at(-1).key, 'ETHUSDT|1h');
});

// --- the rest of what the app can ask

t('Latest scrolls to the newest candle and closes any menu', () => {
  const env = load();
  env.lab.openIndicators();
  env.lab.latest();
  assert.ok(env.calls.some((c) => c[0] === 'scrollToRealTime'));
  assert.equal(env.el('menu').style.display, 'none');
});

t('the live price is a line at the newest candle, replaced each time, and never drawn with no data or a bad price', () => {
  const env = load();
  env.lab.setLastPrice(2.5);
  assert.equal(env.inGroup('live').length, 0, 'no candles yet');
  show(env, 'BTCUSDT|1h');
  env.lab.setLastPrice(2.5);
  env.lab.setLastPrice(2.75);
  assert.equal(env.inGroup('live').length, 2, 'one line and its tag, the older ones removed');
  assert.equal(env.inGroup('live')[0].points[0].value, 2.75);
  assert.equal(env.inGroup('live')[0].points[0].timestamp, 2000, 'at the newest candle');
  env.lab.setLastPrice(0);
  env.lab.setLastPrice(NaN);
  assert.equal(env.inGroup('live').length, 0);
});

t('an empty chart shows its message, and a chart with candles hides it', () => {
  const env = load();
  env.lab.setData({ key: 'X|1h', candles: [], levels: [] });
  assert.equal(env.el('empty').style.display, 'block');
  show(env, 'X|1h');
  assert.equal(env.el('empty').style.display, 'none');
});

t('the calls the app and the debug page make are all still there', () => {
  const env = load();
  for (const name of ['setData', 'setLastPrice', 'setIndicators', 'openIndicators', 'openDraw', 'latest', 'benchmark', 'check']) {
    assert.equal(typeof env.lab[name], 'function', name);
  }
  assert.doesNotThrow(() => env.lab.benchmark(50));
});

t('the page sends no report when a bridge is missing, as in the debug page', () => {
  const env = load();
  // The debug chart check has a bridge with only `report`; the page must cope with the others being absent.
  const ctx = vm.createContext({ ...{}, document: { getElementById: () => new El('div'), createElement: () => new El('div'), createTextNode: () => ({}) },
    klinecharts: { init: () => env.chart, registerIndicator: () => {}, registerOverlay: () => {} }, performance: { now: () => 0 },
    requestAnimationFrame: () => {}, console, addEventListener: () => {}, Android: { report: () => {} } });
  ctx.window = ctx;
  vm.runInContext(pageScript, ctx);
  assert.doesNotThrow(() => ctx.signalLab.setIndicators(['VOL', 'RSI']));
});

// --- what is drawn over the candles

t('the line over the candles reads O H L C Vol, with no date, figures written as the app writes them', () => {
  const env = load();
  const tip = env.chart.styles.candle.tooltip;
  assert.equal(tip.showRule, 'always');
  const legend = tip.custom({ current: { open: 2.85, high: 2.891, low: 2.83, close: 2.86, volume: 208893 } });
  assert.deepEqual([...legend.map((l) => `${l.title}${l.value.text}`)], ['O 2.850', 'H 2.891', 'L 2.830', 'C 2.860', 'Vol 208.9K']);
  assert.equal(tip.custom({}).length, 0, 'nothing under the finger: no line');
});

t('volume is bars in a low panel with no caption of its own', () => {
  const env = load();
  const vol = env.registered.VOLBARS;
  assert.ok(vol, 'the page makes its own volume indicator');
  assert.deepEqual(JSON.parse(JSON.stringify(vol.createTooltipDataSource())), { name: '', calcParamsText: '', values: [], icons: [] });
  assert.deepEqual(JSON.parse(JSON.stringify(vol.calc([{ volume: 5 }, { volume: 7 }]))), [{ volume: 5 }, { volume: 7 }]);
});

t('each level is one overlay carrying its words, its colour and the prices of the other levels', () => {
  const env = load();
  show(env, 'BTCUSDT|1h', [], [
    { kind: 'TARGET', label: 'Target 2.949', price: 2.949 }, { kind: 'ENTRY', label: 'Entry 2.863', price: 2.863 }, { kind: 'STOP', label: 'Stop 2.397', price: 2.397 },
  ]);
  const levels = env.inGroup('levels');
  assert.deepEqual(levels.map((o) => o.name), ['level', 'level', 'level']);
  assert.deepEqual(levels.map((o) => o.extendData.text), ['Target 2.949', 'Entry 2.863', 'Stop 2.397']);
  assert.deepEqual(levels.map((o) => o.extendData.color), ['#089981', '#5b8dff', '#f23645']);
  assert.deepEqual([...levels[1].extendData.others], [2.949, 2.397]);
  assert.ok(levels.every((o) => o.lock), 'a level cannot be dragged');
});

t('a level label sits above its line, and under it when another level is just above', () => {
  const env = load();
  const draw = env.registered.level.createPointFigures;
  const yAxis = { convertToPixel: (v) => 1000 - v * 100 }; // a higher price is higher on the screen
  const label = (price, others) => draw({ coordinates: [{ x: 0, y: yAxis.convertToPixel(price) }], bounding: { width: 400 }, yAxis,
    overlay: { extendData: { text: 'x', color: '#fff', others } } })[1].attrs.baseline;
  assert.equal(label(2.0, []), 'bottom', 'alone: above its line');
  assert.equal(label(2.0, [2.25]), 'bottom', 'the next level 25 px up leaves room');
  assert.equal(label(2.0, [2.19]), 'top', 'at 19 px the lower label goes under its line');
  assert.equal(label(2.0, [2.1]), 'top', 'and at 10 px');
  assert.equal(label(2.1, [2.0]), 'bottom', 'the upper one stays above');
});

t('what to tap while drawing floats over the chart and goes when the drawing is done', () => {
  const env = load();
  show(env, 'BTCUSDT|1h');
  assert.notEqual(env.el('hint').style.display, 'block', 'nothing to say yet');
  env.lab.openDraw();
  env.el('menu').children[0].click();
  assert.equal(env.el('hint').style.display, 'block');
  env.inGroup('draw')[0].onDrawEnd();
  assert.equal(env.el('hint').style.display, 'none');
});
