// Manual integration check: Node https/ws connect directly without proxy agents.
const { LiveMarket } = require('../src/live-market');
const { ContractNetwork, REST } = require('../src/market-network');
const symbols = ['BTCUSDT', 'ETHUSDT', 'MUUSDT', 'SNDKUSDT', 'SKHYNIXUSDT'];
const started = Date.now();
const counts = {}, first = {}, gaps = {}, previous = {};
const network = new ContractNetwork();
const live = new LiveMarket({ onData: d => {
  if (d.e !== 'aggTrade') return;
  const now = Date.now();
  first[d.s] ??= now - started;
  counts[d.s] = (counts[d.s] || 0) + 1;
  if (previous[d.s]) (gaps[d.s] ??= []).push(now - previous[d.s]);
  previous[d.s] = now;
} });
live.start(symbols);
const rest = Promise.allSettled(symbols.map(async symbol => {
  const d = await network.getJson(`${REST}/ticker/24hr?symbol=${symbol}`);
  if (!(Number(d.lastPrice) > 0)) throw new Error(`${symbol} invalid snapshot`);
  return { symbol, ms: Date.now() - started };
}));
setTimeout(async () => {
  live.stop();
  const snapshots = (await rest).map(r => r.status === 'fulfilled' ? r : { status: r.status, reason: r.reason.message });
  const report = symbols.map(symbol => {
    const intervals = (gaps[symbol] || []).sort((a, b) => a - b);
    return { symbol, firstMs: first[symbol], trades: counts[symbol] || 0,
      medianGapMs: intervals[Math.floor(intervals.length / 2)] };
  });
  console.log(JSON.stringify({ direct: true, elapsedMs: Date.now() - started, report, snapshots }, null, 2));
  if (report.some(r => !r.trades) || snapshots.some(r => r.status !== 'fulfilled')) process.exitCode = 1;
}, 15000);
