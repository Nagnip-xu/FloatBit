const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { EventEmitter } = require('node:events');
const markets = require('../src/markets');

const info = (base, status = 'TRADING', contractType = 'TRADIFI_PERPETUAL') => ({
  symbol: `${base}USDT`, baseAsset: base, quoteAsset: 'USDT', status, contractType
});

test('包含普通永续和股票合约，排除非交易状态；精确代码排在模糊匹配前', () => {
  const list = markets.catalog({ symbols: [info('MUBARAK'), info('MU'), info('SNDK'), info('SKHYNIX'),
    info('CSOPSKHYNIX2L'), info('BTC', 'TRADING', 'PERPETUAL'), info('OLD', 'SETTLING')] });
  for (const coin of ['MU', 'SNDK', 'SKHYNIX']) assert.equal(markets.search(list, coin)[0].base, coin);
  assert.equal(markets.search(list, ' mu / usdt ')[0].symbol, 'MUUSDT');
  assert.equal(markets.search(list, 'BTC')[0].base, 'BTC');
  assert.deepEqual(markets.search(list, 'OLD'), []);
  assert.deepEqual(markets.search(list, 'NVDAB'), []);
  assert.equal(markets.parse('SKHYNIXUSDT').coin, 'SKHYNIX');
});

function harness() {
  const urls = [], sockets = [], handlers = new Map();
  let responder = () => ({ symbols: [info('MU'), info('SNDK'), info('SKHYNIX')] });
  class Socket extends EventEmitter {
    constructor(url) { super(); this.url = url; this.readyState = 1; sockets.push(this); }
    terminate() { this.readyState = 3; }
  }
  const electron = {
    app: { getPath: () => '.', requestSingleInstanceLock: () => false, quit() {}, on() {} },
    ipcMain: { handle: (name, handler) => handlers.set(name, handler), on() {} }
  };
  class ContractNetwork {
    async getJson(url) {
      urls.push(url);
      const result = await responder(new URL(url));
      if (result.code) throw Object.assign(new Error(result.msg || 'API error'), { code: result.code });
      return result;
    }
  }
  const realLive = require('../src/live-market');
  class LiveMarket extends realLive.LiveMarket {
    constructor(options) { super({ ...options, createSocket: url => new Socket(url) }); }
  }
  const context = vm.createContext({
    require: (name) => name === 'electron' ? electron : name === 'ws' ? Socket : name === './markets' ? markets :
      name === './market-network' ? { ContractNetwork } : name === './live-market' ? { ...realLive, LiveMarket } :
      name === './ticker-publisher' ? { TickerPublisher: class { schedule() {} stop() {} } } :
      name === 'fs' ? { readFileSync() { throw new Error('No config'); }, writeFileSync() {} } : require(name),
    __dirname: path.join(__dirname, '../src'), console: { error() {} },
    setTimeout: () => 1, clearTimeout() {}, setInterval() {}, clearInterval() {}
  });
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../src/main.js'), 'utf8') +
    '\nglobalThis.subject = { fetchSymbols, fetchTicker, fetchKline, connectWs, resubscribeWs, config, tickerCache, live, getDayOpen };', context);
  return { subject: context.subject, urls, sockets, setResponse: (fn) => { responder = fn; } };
}

test('合约列表并发去重、60 秒缓存与手动刷新；失败保留旧结果并报告错误', async () => {
  const h = harness();
  await Promise.all([h.subject.fetchSymbols(), h.subject.fetchSymbols()]);
  assert.equal(h.urls.length, 1);
  await h.subject.fetchSymbols(); assert.equal(h.urls.length, 1);
  h.setResponse(() => ({ symbols: [info('MU'), info('NEW')] }));
  assert.equal((await h.subject.fetchSymbols(true)).symbols[1].base, 'NEW');
  h.setResponse(() => ({ code: -1121, msg: 'Invalid symbol.' }));
  const stale = await h.subject.fetchSymbols(true);
  assert.equal(stale.symbols[1].base, 'NEW'); assert.equal(stale.errors.length, 1);
  assert.ok(h.urls.every((url) => url === 'https://fapi.binance.com/fapi/v1/exchangeInfo'));
});

test('价格、日线与走势图均路由至合约接口，现货专有代码明确报错', async () => {
  const h = harness();
  h.setResponse((url) => url.searchParams.get('symbol') === 'NVDABUSDT' ? { code: -1121, msg: 'Invalid symbol.' } :
    url.pathname.endsWith('/klines') ? [[Date.UTC(new Date().getUTCFullYear(), new Date().getUTCMonth(), new Date().getUTCDate()), '100', '120', '90', '110']] :
      { symbol: url.searchParams.get('symbol'), lastPrice: '110', priceChangePercent: '8', openPrice: '101', highPrice: '120', lowPrice: '90' });
  h.subject.config.symbols = ['MUUSDT', 'NVDABUSDT'];
  await h.subject.getDayOpen('MUUSDT');
  const t = await h.subject.fetchTicker('MUUSDT');
  assert.equal(t.ok, true); assert.equal(t.symbol, 'MUUSDT'); assert.equal(t.last, 110);
  assert.ok(Math.abs(t.changePct - 10) < 0.0001);
  assert.equal((await h.subject.fetchKline('SKHYNIXUSDT')).closes[0], 110);
  assert.equal((await h.subject.fetchTicker('NVDABUSDT')).error, '无此合约');
  assert.ok(h.urls.every((url) => url.startsWith('https://fapi.binance.com/fapi/v1/')));
});

test('WebSocket 订阅与改选只使用合约流，保留完整长代码', () => {
  const h = harness();
  h.subject.config.symbols = ['MUUSDT', 'SKHYNIXUSDT']; h.subject.connectWs();
  assert.equal(h.sockets[0].url, 'wss://fstream.binance.com/market/stream?streams=muusdt@aggTrade/muusdt@ticker/skhynixusdt@aggTrade/skhynixusdt@ticker');
  h.sockets[0].emit('message', Buffer.from(JSON.stringify({ data: { e: '24hrTicker', s: 'SKHYNIXUSDT', c: '830.91', P: '-2.435', C: 200, o: '850' } })));
  assert.equal(h.subject.tickerCache.SKHYNIXUSDT.last, 830.91);
  h.subject.config.symbols = ['SNDKUSDT']; h.subject.resubscribeWs();
  assert.equal(h.sockets[0].readyState, 3);
  assert.equal(h.sockets[2].url, 'wss://fstream.binance.com/market/stream?streams=sndkusdt@aggTrade/sndkusdt@ticker');
  assert.equal(h.subject.tickerCache.SKHYNIXUSDT, undefined);
  h.subject.live.stop();
});


test('slow daily candle does not block the first price or other symbols', async () => {
  const h = harness();
  h.subject.config.symbols = ['MUUSDT'];
  h.setResponse(url => url.pathname.endsWith('/klines') ? new Promise(() => {}) :
    { lastPrice: '110', openPrice: '100', priceChangePercent: '10', closeTime: 200 });
  h.subject.connectWs();
  try {
    await h.subject.fetchTicker('MUUSDT');
    assert.equal(h.subject.tickerCache.MUUSDT.last, 110);
    assert.equal(h.subject.tickerCache.MUUSDT.changePct, 10);
  } finally { h.subject.live.stop(); }
});
