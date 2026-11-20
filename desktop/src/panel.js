const api = window.floatbit;

// 热门币种 (计价均为 USDT)
const HOT_COINS = [
  'BTC', 'ETH', 'BNB', 'SOL',
  'XRP', 'DOGE', 'ADA', 'TRX',
  'AVAX', 'LINK', 'DOT', 'SHIB',
  'PEPE', 'LTC', 'NEAR', 'ARB'
];

let config = null;
let allSymbols = []; // [{symbol, base, quote}]
let symbolsLoading = false;
let symbolsError = '';

const els = {
  watchList: document.getElementById('watch-list'),
  watchEmpty: document.getElementById('watch-empty'),
  watchCount: document.getElementById('watch-count'),
  searchInput: document.getElementById('search-input'),
  searchResults: document.getElementById('search-results'),
  hotGrid: document.getElementById('hot-grid'),
  colorSeg: document.getElementById('color-seg'),
  opacityInput: document.getElementById('opacity-input'),
  opacityVal: document.getElementById('opacity-val'),
  ontopInput: document.getElementById('ontop-input'),
  themeToggle: document.getElementById('theme-toggle'),
  toast: document.getElementById('toast')
};

// ---------- 主题 ----------
function applyTheme(theme) {
  const t = theme === 'light' ? 'light' : 'dark';
  document.documentElement.setAttribute('data-theme', t);
  // 暗色显示月亮(点击切到亮色), 亮色显示太阳
  els.themeToggle.textContent = t === 'dark' ? '🌙' : '☀';
  els.themeToggle.title = t === 'dark' ? '切换到亮色' : '切换到暗色';
}

// ---------- 涨跌配色 ----------
function applyColorScheme(scheme) {
  const s = scheme === 'red-up' ? 'red-up' : 'green-up';
  document.documentElement.setAttribute('data-color', s);
}

// ---------- 工具 ----------
function splitSymbol(symbol) {
  return window.FloatBitMarkets.parse(symbol);
}

let toastTimer = null;
function toast(msg, warn = false) {
  els.toast.textContent = msg;
  els.toast.classList.toggle('warn', warn);
  els.toast.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => els.toast.classList.remove('show'), 1800);
}

// ---------- Sparkline 走势图 (SVG) ----------
function buildSparkline(closes, up, w = 72, h = 28) {
  if (!closes || closes.length < 2) return '';
  const min = Math.min(...closes);
  const max = Math.max(...closes);
  const range = max - min || 1;
  const n = closes.length;
  const pad = 2;
  const innerW = w - pad * 2;
  const innerH = h - pad * 2;
  const pts = closes.map((c, i) => {
    const x = pad + (i / (n - 1)) * innerW;
    const y = pad + (1 - (c - min) / range) * innerH;
    return [x, y];
  });
  const line = pts.map((p) => `${p[0].toFixed(1)},${p[1].toFixed(1)}`).join(' ');
  const area = `${pad},${h} ${line} ${(w - pad)},${h}`;
  const color = up ? 'var(--up)' : 'var(--down)';
  const gid = 'sg' + Math.random().toString(36).slice(2, 8);
  return `
    <svg class="spark" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}" preserveAspectRatio="none">
      <defs>
        <linearGradient id="${gid}" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stop-color="${color}" stop-opacity="0.28"/>
          <stop offset="100%" stop-color="${color}" stop-opacity="0"/>
        </linearGradient>
      </defs>
      <polygon points="${area}" fill="url(#${gid})" />
      <polyline points="${line}" fill="none" stroke="${color}" stroke-width="1.5"
        stroke-linejoin="round" stroke-linecap="round" />
    </svg>`;
}

// ---------- 配置写入 ----------
async function saveSymbols(symbols) {
  config = await api.setConfig({ symbols });
  renderWatch();
  renderHot();
  renderSearch(els.searchInput.value.trim());
  refreshKlines();
  refreshPrices();
}

function hasSymbol(symbol) {
  return config.symbols.includes(symbol);
}

async function addSymbol(symbol) {
  if (hasSymbol(symbol)) return;
  await saveSymbols([...config.symbols, symbol]);
  const { coin } = splitSymbol(symbol);
  toast(`已添加 ${coin}`);
}

async function removeSymbol(symbol) {
  await saveSymbols(config.symbols.filter((s) => s !== symbol));
  const { coin } = splitSymbol(symbol);
  toast(`已移除 ${coin}`, true);
}

// ---------- 渲染: 我的自选 ----------
function renderWatch() {
  els.watchCount.textContent = `${config.symbols.length} 个`;
  els.watchList.innerHTML = '';

  if (!config.symbols.length) {
    els.watchEmpty.classList.remove('hidden');
    return;
  }
  els.watchEmpty.classList.add('hidden');

  config.symbols.forEach((symbol) => {
    const { coin, pair } = splitSymbol(symbol);
    const item = document.createElement('div');
    item.className = 'watch-item';
    item.innerHTML = `
      <div class="info">
        <span class="coin" title="${coin}">${coin}</span>
        <span class="pair">${pair}</span>
      </div>
      <div class="spark-wrap" data-spark="${symbol}"></div>
      <div class="right">
        <span class="price" data-sym="${symbol}">--</span>
        <button class="del" title="移除">✕</button>
      </div>`;
    item.querySelector('.del').addEventListener('click', () => removeSymbol(symbol));
    els.watchList.appendChild(item);
  });

  // 渲染缓存中的走势图
  applySparklines();
}

// 走势图数据缓存 { symbol: closes[] }
let klineCache = {};
let changeMap = {}; // { symbol: changePct } 走势图颜色跟随当日涨跌, 与价格一致

function applySparklines() {
  config.symbols.forEach((symbol) => {
    const wrap = els.watchList.querySelector(`.spark-wrap[data-spark="${symbol}"]`);
    if (!wrap) return;
    const closes = klineCache[symbol];
    if (closes && closes.length >= 2) {
      // 颜色跟随当日涨跌幅(与价格一致); 拿不到时回退到K线首末比较
      const chg = changeMap[symbol];
      const up = (chg !== undefined && isFinite(chg))
        ? chg >= 0
        : closes[closes.length - 1] >= closes[0];
      wrap.innerHTML = buildSparkline(closes, up);
    }
  });
}

async function refreshKlines() {
  if (!config.symbols.length) return;
  try {
    klineCache = await api.getKlines(config.symbols);
    applySparklines();
  } catch {}
}

// ---------- 渲染: 热门 ----------
function renderHot() {
  els.hotGrid.innerHTML = '';
  HOT_COINS.forEach((coin) => {
    const symbol = `${coin}USDT`;
    const added = hasSymbol(symbol);
    const available = allSymbols.some((s) => s.symbol === symbol);
    const chip = document.createElement('div');
    chip.className = 'hot-chip' + (added || !available ? ' added' : '');
    chip.innerHTML = `<span class="h-coin">${coin}</span><span class="h-pair">USDT</span>`;
    if (!added && available) {
      chip.addEventListener('click', () => addSymbol(symbol));
    }
    els.hotGrid.appendChild(chip);
  });
}

// ---------- 渲染: 搜索结果 ----------
function renderSearch(keyword) {
  els.searchResults.innerHTML = '';
  const kw = (keyword || '').trim().toUpperCase();
  if (!kw) return;

  const matched = window.FloatBitMarkets.search(allSymbols, kw);

  if (!matched.length) {
    const message = symbolsLoading ? '正在更新合约列表…' : symbolsError || '没有找到可交易的合约，请尝试完整代码（如 MU、SNDK、SKHYNIX）';
    const hint = document.createElement('div');
    hint.className = 'no-result';
    hint.textContent = message;
    els.searchResults.appendChild(hint);
    return;
  }

  matched.forEach((s) => {
    const added = hasSymbol(s.symbol);
    const item = document.createElement('div');
    item.className = 'result-item' + (added ? ' added' : '');
    item.innerHTML = `
      <div class="r-left">
        <span class="r-coin">${s.base}</span>
        <span class="r-pair">${s.quote} · 合约</span>
      </div>
      <span class="r-add">${added ? '已添加' : '+ 添加'}</span>`;
    if (!added) {
      item.addEventListener('click', () => addSymbol(s.symbol));
    }
    els.searchResults.appendChild(item);
  });
}

async function refreshSymbols(force = false) {
  if (symbolsLoading) return;
  symbolsLoading = true;
  renderSearch(els.searchInput.value);
  try {
    const result = await api.getSymbols(force);
    allSymbols = result.symbols;
    symbolsError = result.errors.length ? `${result.errors.join('；')}，请点击刷新重试` : '';
  } catch {
    symbolsError = '合约列表加载失败，请检查网络并点击刷新';
  } finally {
    symbolsLoading = false;
    document.getElementById('symbols-status').textContent = symbolsError || `币安 USDⓈ-M 合约 · ${allSymbols.length} 个交易对`;
    renderSearch(els.searchInput.value);
    renderHot();
  }
}

// ---------- 自选实时价格 ----------
function applyPrices(tickers) {
  let changed = false;
  tickers.forEach((t) => {
    if (t.ok && isFinite(t.changePct) && changeMap[t.symbol] === undefined) changed = true;
    if (t.ok && isFinite(t.changePct)) changeMap[t.symbol] = t.changePct;
    const el = els.watchList.querySelector(`.price[data-sym="${t.symbol}"]`);
    if (!el) return;
    if (t.ok) {
      const price = t.last >= 1000
        ? t.last.toLocaleString('en-US', { maximumFractionDigits: 2 })
        : t.last >= 1 ? t.last.toFixed(2)
        : t.last >= 0.01 ? t.last.toFixed(4)
        : t.last.toPrecision(4);
      const up = t.changePct >= 0;
      const sign = up ? '+' : '';
      el.innerHTML = `${price} <span style="color:${up ? 'var(--up)' : 'var(--down)'};font-size:11px">${sign}${t.changePct.toFixed(2)}%</span>`;
      el.style.color = 'var(--text)';
    } else {
      el.textContent = t.error || '错误';
      el.title = t.error || '错误';
    }
  });
  // 首次拿到当日涨跌数据后, 刷新走势图颜色使其与价格一致
  if (changed) applySparklines();
}

// 初始用 REST 拉一次, 之后由 WebSocket 推送驱动
async function refreshPrices() {
  try {
    const tickers = await api.getTickers();
    applyPrices(tickers);
  } catch {}
}

// ---------- 设置 ----------
function renderSettings() {
  // 不透明度
  const op = Math.round(config.opacity * 100);
  els.opacityInput.value = op;
  els.opacityVal.textContent = `${op}%`;
  // 置顶
  els.ontopInput.checked = config.alwaysOnTop;
  // 涨跌配色
  const scheme = config.colorScheme === 'red-up' ? 'red-up' : 'green-up';
  els.colorSeg.querySelectorAll('button').forEach((b) => {
    b.classList.toggle('active', b.dataset.color === scheme);
  });
}

els.colorSeg.addEventListener('click', async (e) => {
  const btn = e.target.closest('button');
  if (!btn) return;
  const scheme = btn.dataset.color;
  applyColorScheme(scheme);
  config = await api.setConfig({ colorScheme: scheme });
  renderSettings();
  // 重新渲染价格与走势图以套用新颜色
  applySparklines();
  refreshPrices();
  toast(scheme === 'red-up' ? '已设为红涨绿跌' : '已设为绿涨红跌');
});

els.opacityInput.addEventListener('input', (e) => {
  els.opacityVal.textContent = `${e.target.value}%`;
});
els.opacityInput.addEventListener('change', async (e) => {
  config = await api.setConfig({ opacity: parseInt(e.target.value, 10) / 100 });
});

els.ontopInput.addEventListener('change', async (e) => {
  config = await api.setConfig({ alwaysOnTop: e.target.checked });
  toast(e.target.checked ? '悬浮窗已置顶' : '取消置顶');
});

document.getElementById('show-float').addEventListener('click', () => {
  api.showFloating();
  toast('已唤回悬浮窗');
});

// 邮箱链接 -> 系统默认邮件客户端
const authorMail = document.getElementById('author-mail');
if (authorMail) {
  authorMail.addEventListener('click', (e) => {
    e.preventDefault();
    api.openExternal('mailto:xu814667@gmail.com');
  });
}

// ---------- 搜索输入 ----------
let searchDebounce = null;
els.searchInput.addEventListener('input', (e) => {
  clearTimeout(searchDebounce);
  const val = e.target.value;
  searchDebounce = setTimeout(() => renderSearch(val), 120);
});

// ---------- 窗口控制 ----------
document.getElementById('win-min').addEventListener('click', () => api.panelMinimize());
document.getElementById('win-close').addEventListener('click', () => api.panelClose());

// ---------- 主题切换 ----------
els.themeToggle.addEventListener('click', async () => {
  const next = config.theme === 'light' ? 'dark' : 'light';
  applyTheme(next);
  config = await api.setConfig({ theme: next });
  toast(next === 'light' ? '已切换亮色' : '已切换暗色');
});

// ---------- 初始化 ----------
(async function init() {
  config = await api.getConfig();
  applyTheme(config.theme);
  applyColorScheme(config.colorScheme);
  renderWatch();
  renderHot();
  renderSettings();

  // 拉取全部交易对 (用于搜索)
  refreshSymbols();
  document.getElementById('refresh-symbols').addEventListener('click', () => refreshSymbols(true));
  window.addEventListener('focus', () => refreshSymbols());
  setInterval(refreshSymbols, 60000);

  // 初始价格 (REST 拉一次), 之后由 WebSocket 实时推送驱动
  refreshPrices();
  api.onTickersUpdate((list) => applyPrices(list));

  // 走势图 (每 30s 刷新一次即可)
  refreshKlines();
  setInterval(refreshKlines, 30000);
})();
