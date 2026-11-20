(function (root, factory) {
  const markets = factory();
  if (typeof module === 'object' && module.exports) module.exports = markets;
  else root.FloatBitMarkets = markets;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  const sources = {
    futures: { label: '合约', rest: 'https://fapi.binance.com/fapi/v1', ws: 'wss://fstream.binance.com/stream' }
  };
  const quotes = ['FDUSD', 'USDT', 'USDC', 'USD1', 'TUSD', 'BUSD', 'USDP', 'DAI', 'USD', 'BTC', 'ETH', 'BNB', 'EUR', 'TRY', 'BRL', 'ARS', 'PLN', 'ZAR', 'JPY', 'MXN'];

  function id(symbol) {
    return symbol;
  }

  function parse(value) {
    const market = 'futures';
    const symbol = value;
    const quote = quotes.find((q) => symbol.endsWith(q) && symbol.length > q.length) || '';
    return { market, symbol, coin: quote ? symbol.slice(0, -quote.length) : symbol, pair: quote, label: sources[market].label };
  }

  function catalog(data) {
    if (!data || !Array.isArray(data.symbols)) throw new Error('交易对列表格式错误');
    return data.symbols
      .filter((s) => s.status === 'TRADING' && s.symbol && s.baseAsset && s.quoteAsset)
      .map((s) => ({ symbol: s.symbol, base: s.baseAsset, quote: s.quoteAsset }));
  }

  function search(symbols, keyword, limit = 30) {
    const kw = keyword.trim().toUpperCase().replace(/[\s/_-]/g, '');
    if (!kw) return [];
    const matchRank = (s) => s.base === kw ? 0 : parse(s.symbol).symbol === kw ? 1 : s.base.startsWith(kw) ? 2 : 3;
    const quoteRank = (q) => q === 'USDT' ? 0 : q === 'USDC' ? 1 : 2;
    return symbols.filter((s) => parse(s.symbol).symbol.includes(kw) || s.base.includes(kw))
      .sort((a, b) => matchRank(a) - matchRank(b) || quoteRank(a.quote) - quoteRank(b.quote) || a.symbol.localeCompare(b.symbol))
      .slice(0, limit);
  }

  function pairLabel(value) {
    const p = parse(value);
    return `${p.pair} · ${p.label}`;
  }

  return { sources, id, parse, catalog, search, pairLabel };
});
