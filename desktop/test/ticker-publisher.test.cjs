const { test } = require('node:test');
const assert = require('node:assert/strict');
const { TickerPublisher } = require('../src/ticker-publisher');

function harness() {
  let time = 0, nextId = 0, latest = 100;
  const timers = new Map(), published = [];
  const publisher = new TickerPublisher(() => published.push({ time, price: latest }), {
    now: () => time,
    schedule: (callback, delay) => { const id = ++nextId; timers.set(id, { callback, due: time + delay }); return id; },
    cancel: id => timers.delete(id)
  });
  return { publisher, published, timers,
    trade(price) { latest = price; publisher.schedule(); },
    advance(to) {
      for (;;) {
        const next = [...timers].filter(([, t]) => t.due <= to).sort((a, b) => a[1].due - b[1].due)[0];
        if (!next) break;
        timers.delete(next[0]); time = next[1].due; next[1].callback();
      }
      time = to;
    }
  };
}
test('first price appears immediately; a trade burst displays only the latest price once per second', () => {
  const h = harness();
  h.trade(100);
  assert.deepEqual(h.published, [{ time: 0, price: 100 }]);
  for (let t = 10; t < 1000; t += 10) { h.advance(t); h.trade(100 + t); }
  assert.equal(h.published.length, 1);
  assert.equal(h.timers.size, 1);
  h.advance(1000);
  assert.deepEqual(h.published.at(-1), { time: 1000, price: 1090 });
  h.advance(2000);
  assert.equal(h.published.length, 2); // No repeated renders without incoming data.
});
test('a quiet market publishes immediately after an idle interval', () => {
  const h = harness(); h.trade(100); h.advance(5000); h.trade(101);
  assert.deepEqual(h.published.at(-1), { time: 5000, price: 101 });
  assert.equal(h.timers.size, 0);
});
test('quitting cancels the pending display update', () => {
  const h = harness(); h.trade(100); h.advance(100); h.trade(101);
  h.publisher.stop(); h.advance(2000); h.trade(102);
  assert.equal(h.timers.size, 0);
  assert.equal(h.published.length, 1);
});
