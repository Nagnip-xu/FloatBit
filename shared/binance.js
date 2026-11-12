/**
 * FloatBit 共享行情模块 — 币安 (Binance) 公共行情接口封装
 *
 * 这是一份与平台无关的纯逻辑代码 (不依赖 Electron / 浏览器特有 API),
 * 桌面版、网页版、未来的手机版都可以复用。
 *
 * 用法 (浏览器 / 手机 WebView 环境, 用全局 fetch):
 *   import { getTickers, getKlines, getSymbols } from './binance.js';
 *
 * 数据来源: 币安公共行情专用域名 data-api.binance.vision (免密钥)
 */

export const BINANCE_HOST = 'https://data-api.binance.vision';

/**
 * 获取一个或多个交易对的 24h 行情
 * @param {string[]} symbols 例如 ['BTCUSDT', 'ETHUSDT']
 * @param {function} fetchFn 自定义 fetch (Electron 主进程可传 net 封装); 默认用全局 fetch
 * @returns {Promise<Array>} 标准化后的行情数组
 */
export async function getTickers(symbols, fetchFn = fetch) {
  const param = encodeURIComponent(JSON.stringify(symbols));
  const res = await fetchFn(`${BINANCE_HOST}/api/v3/ticker/24hr?symbols=${param}`);
  const data = await res.json();
  const arr = Array.isArray(data) ? data : [data];
  return arr.map((d) => ({
    symbol: d.symbol,
    last: parseFloat(d.lastPrice),
    open: parseFloat(d.openPrice),
    high: parseFloat(d.highPrice),
    low: parseFloat(d.lowPrice),
    changePct: parseFloat(d.priceChangePercent)
  }));
}

/**
 * 获取 K线 收盘价序列 (用于走势图)
 * @param {string} symbol 交易对, 如 'BTCUSDT'
 * @param {string} interval K线周期, 如 '15m' / '1d'
 * @param {number} limit 条数
 */
export async function getKlines(symbol, interval = '15m', limit = 30, fetchFn = fetch) {
  const res = await fetchFn(`${BINANCE_HOST}/api/v3/klines?symbol=${symbol}&interval=${interval}&limit=${limit}`);
  const data = await res.json();
  if (!Array.isArray(data)) return [];
  return data.map((k) => parseFloat(k[4])).filter((n) => isFinite(n));
}

/**
 * 获取全部可交易的交易对 (用于搜索 / 添加)
 */
export async function getSymbols(fetchFn = fetch) {
  const res = await fetchFn(`${BINANCE_HOST}/api/v3/exchangeInfo`);
  const data = await res.json();
  if (!data || !Array.isArray(data.symbols)) return [];
  return data.symbols
    .filter((s) => s.status === 'TRADING')
    .map((s) => ({ symbol: s.symbol, base: s.baseAsset, quote: s.quoteAsset }));
}

/* ---------- 与平台无关的小工具 ---------- */

export function formatPrice(n) {
  if (!isFinite(n)) return '--';
  if (n >= 1000) return n.toLocaleString('en-US', { maximumFractionDigits: 2 });
  if (n >= 1) return n.toFixed(2);
  if (n >= 0.01) return n.toFixed(4);
  return n.toPrecision(4);
}

export function formatPct(p) {
  if (!isFinite(p)) return '';
  return `${p >= 0 ? '+' : ''}${p.toFixed(2)}%`;
}

export function splitSymbol(symbol) {
  const quotes = ['USDT', 'USDC', 'USD1', 'USD', 'FDUSD', 'BTC', 'ETH'];
  for (const q of quotes) {
    if (symbol.endsWith(q) && symbol.length > q.length) {
      return { coin: symbol.slice(0, symbol.length - q.length), pair: q };
    }
  }
  return { coin: symbol, pair: '' };
}
