const { test } = require('node:test');
const assert = require('node:assert/strict');
const { EventEmitter } = require('node:events');
const { LiveMarket, streamsFor, mergeEvent, mergeSnapshot } = require('../src/live-market');
const { ContractNetwork, HttpError, parseCname, REST } = require('../src/market-network');

test('subscribe to aggregate trades and contract statistics for every selected symbol', () => {
  assert.equal(streamsFor(['MUUSDT']), 'muusdt@aggTrade/muusdt@ticker');
});
test('a silent primary handshake cannot prevent the alternate receiving real trades', () => {
  const sockets = [], events = [], states = [];
  class Socket extends EventEmitter { terminate() { this.terminated = true; } }
  const live = new LiveMarket({ onData: d => events.push(d), onStatus: s => states.push(s),
    createSocket: () => { const s = new Socket(); sockets.push(s); return s; } });
  live.start(['MUUSDT']);
  try {
    sockets[0].emit('message', Buffer.from('{"result":null}'));
    assert.equal(live.winner, null);
    sockets[1].emit('message', Buffer.from('{"data":{"e":"aggTrade","s":"MUUSDT","p":"100","T":200}}'));
    assert.equal(live.winner, sockets[1]);
    assert.equal(sockets[0].terminated, true);
    assert.equal(events.length, 1);
    assert.equal(states.at(-1), 'live');
    live.start(['SNDKUSDT']);
    sockets[1].emit('message', Buffer.from('{"data":{"e":"aggTrade","s":"MUUSDT","p":"999","T":300}}'));
    assert.equal(events.length, 1);
    assert.equal(sockets[1].terminated, true);
  } finally { live.stop(); }
});
test('late ticker fills statistics without rolling back a newer trade', () => {
  const trade = mergeEvent(null, { e: 'aggTrade', s: 'MUUSDT', p: '110', T: 200 });
  assert.equal(trade.last, 110);
  assert.ok(Number.isNaN(trade.changePct));
  const value = mergeEvent(trade, { e: '24hrTicker', s: 'MUUSDT', c: '100', C: 100, o: '100', h: '120', l: '90' });
  assert.equal(value.last, 110);
  assert.equal(value.changePct, 10);
  assert.equal(value.eventTime, 200);
});
test('REST started before a live event only fills missing statistics', () => {
  const previous = { ok: true, last: 110, eventTime: 200, receivedAt: 500, source: 'live', changePct: NaN };
  const value = mergeSnapshot(previous, { ok: true, last: 100, eventTime: 100, open: 100, changePct: 0 }, 400);
  assert.equal(value.last, 110);
  assert.equal(value.open, 100);
  assert.equal(mergeSnapshot(previous, { ok: false }, 600).last, 110);
});
test('CDN wins immediately while the primary remains blocked, aborting the loser', async () => {
  let primarySignal, host;
  const network = new ContractNetwork({
    get: (url, options) => {
      if (url.startsWith(REST)) { primarySignal = options.signal; return new Promise(() => {}); }
      host = options.host; return Promise.resolve({ lastPrice: '110' });
    }, resolveCdn: async () => ({ base: 'https://test.cloudfront.net/fapi/v1', ttl: 60000 })
  });
  const value = await network.getJson(REST + '/ticker/24hr?symbol=MUUSDT');
  assert.equal(value.lastPrice, '110');
  assert.equal(host, 'fapi.binance.com');
  assert.ok(primarySignal.aborted);
});
test('same REST URL shares a request and rate limits do not trigger a retry loop', async () => {
  let calls = 0;
  const network = new ContractNetwork({ get: async () => { calls++; throw new HttpError(429, {}); }, resolveCdn: async () => null });
  const a = network.getJson(REST + '/exchangeInfo');
  const b = network.getJson(REST + '/exchangeInfo');
  assert.equal(a, b);
  await assert.rejects(a, { status: 429 });
  assert.equal(calls, 1);
});
test('DNS fallback validates the exact Binance CNAME and TLS host', () => {
  const answer = (name, data) => ({ Status: 0, Answer: [{ name, type: 5, data, TTL: 60 }] });
  assert.equal(parseCname(answer('fapi.binance.com.', 'abc.cloudfront.net.')).base, 'https://abc.cloudfront.net/fapi/v1');
  assert.equal(parseCname(answer('other.binance.com.', 'abc.cloudfront.net.')), null);
  assert.equal(parseCname(answer('fapi.binance.com.', 'abc.cloudfront.net.evil.com')), null);
});
