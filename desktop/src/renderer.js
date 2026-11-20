const api = window.floatbit;

const els = {
  list: document.getElementById('list'),
  status: document.getElementById('status'),
  updated: document.getElementById('updated')
};

let config = null;
let lastPrices = {}; // 记录上次价格用于闪烁判断

// ---------- 工具 ----------
function coinName(symbol) {
  return window.FloatBitMarkets.parse(symbol);
}

function formatPrice(n) {
  if (!isFinite(n)) return '--';
  if (n >= 1000) return n.toLocaleString('en-US', { maximumFractionDigits: 2 });
  if (n >= 1) return n.toFixed(2);
  if (n >= 0.01) return n.toFixed(4);
  return n.toPrecision(4);
}

function formatPct(p) {
  if (!isFinite(p)) return '';
  const sign = p > 0 ? '+' : '';
  return `${sign}${p.toFixed(2)}%`;
}

function pad(n) {
  return String(n).padStart(2, '0');
}

// ---------- 涨跌配色 ----------
function applyColorScheme(scheme) {
  const s = scheme === 'red-up' ? 'red-up' : 'green-up';
  document.documentElement.setAttribute('data-color', s);
}

// ---------- Sparkline 走势图 (SVG) ----------
function buildSparkline(closes, up, w = 56, h = 22) {
  if (!closes || closes.length < 2) return '';
  const min = Math.min(...closes);
  const max = Math.max(...closes);
  const range = max - min || 1;
  const n = closes.length;
  const pad2 = 1.5;
  const innerW = w - pad2 * 2;
  const innerH = h - pad2 * 2;
  const pts = closes.map((c, i) => {
    const x = pad2 + (i / (n - 1)) * innerW;
    const y = pad2 + (1 - (c - min) / range) * innerH;
    return [x, y];
  });
  const line = pts.map((p) => `${p[0].toFixed(1)},${p[1].toFixed(1)}`).join(' ');
  const area = `${pad2},${h} ${line} ${(w - pad2)},${h}`;
  const color = up ? 'var(--up)' : 'var(--down)';
  const gid = 'fs' + Math.random().toString(36).slice(2, 8);
  return `
    <svg class="spark" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}" preserveAspectRatio="none">
      <defs>
        <linearGradient id="${gid}" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stop-color="${color}" stop-opacity="0.30"/>
          <stop offset="100%" stop-color="${color}" stop-opacity="0"/>
        </linearGradient>
      </defs>
      <polygon points="${area}" fill="url(#${gid})" />
      <polyline points="${line}" fill="none" stroke="${color}" stroke-width="1.4"
        stroke-linejoin="round" stroke-linecap="round" />
    </svg>`;
}

let klineCache = {}; // { symbol: closes[] }
let changeMap = {}; // { symbol: changePct } 走势图颜色跟随当日涨跌, 与价格一致

function applySparklines() {
  if (!config) return;
  config.symbols.forEach((symbol) => {
    const wrap = els.list.querySelector(`.spark-wrap[data-spark="${symbol}"]`);
    if (!wrap) return;
    const closes = klineCache[symbol];
    if (closes && closes.length >= 2) {
      // 颜色跟随当日涨跌幅(与价格一致); 拿不到时回退到K线首末比较
      const chg = changeMap[symbol];
      const up = (chg !== undefined && isFinite(chg))
        ? chg >= 0
        : closes[closes.length - 1] >= closes[0];
      if (wrap._closes !== closes || wrap._up !== up) {
        wrap._closes = closes; wrap._up = up;
        wrap.innerHTML = buildSparkline(closes, up);
      }
    }
  });
}

async function refreshKlines() {
  if (!config || !config.symbols.length) return;
  try {
    klineCache = await api.getKlines(config.symbols);
    applySparklines();
  } catch {}
}

// ---------- 渲染 ----------
const rows = new Map();
function render(tickers) {
  const selected = new Set(tickers.map(t => t.symbol));
  for (const [symbol, row] of rows) if (!selected.has(symbol)) { row.remove(); rows.delete(symbol); }
  const empty = els.list.querySelector('.loading');
  if (empty) empty.remove();
  for (const t of tickers) {
    let row = rows.get(t.symbol);
    if (!row) {
      const { coin, pair } = coinName(t.symbol);
      row = document.createElement('div'); row.className = 'row';
      row.innerHTML = `<div class="sym"><span class="coin" title="${coin}">${coin}</span><span class="pair">${pair}</span></div>
        <div class="spark-wrap" data-spark="${t.symbol}"></div><div class="right"><span class="price"></span><span class="chg"></span></div>`;
      rows.set(t.symbol, row);
    }
    // 只在顺序发生变化时移动已有节点。
    const position = tickers.indexOf(t);
    if (els.list.children[position] !== row) els.list.insertBefore(row, els.list.children[position] || null);
    const price = row.querySelector('.price');
    const change = row.querySelector('.chg');
    price.classList.toggle('err', !t.ok);
    price.textContent = t.ok ? formatPrice(t.last) : t.error || '连接中';
    price.title = t.source === 'live' ? '合约实时成交' : t.ok ? 'REST 快照，等待实时推送' : price.textContent;
    if (t.ok) {
      const prev = lastPrices[t.symbol];
      if (prev !== undefined && prev !== t.last) {
        price.animate([{ color: t.last > prev ? 'var(--up)' : 'var(--down)' }, { color: 'var(--text)' }], { duration: 250 });
      }
      lastPrices[t.symbol] = t.last;
      if (isFinite(t.changePct)) changeMap[t.symbol] = t.changePct;
    }
    change.textContent = t.ok ? formatPct(t.changePct) : '';
    change.className = 'chg ' + (t.changePct >= 0 ? 'up' : 'down');
  }
  if (!tickers.length) els.list.innerHTML = '<div class="loading">点击 ⚙ 添加币种</div>';
  applySparklines();
}
let streamStatus = 'connecting';
function setStatus() {
  els.status.classList.toggle('off', streamStatus !== 'live');
  const d = new Date();
  const label = streamStatus === 'live' ? '实时' : streamStatus === 'reconnecting' ? '重连中' : '连接中';
  els.status.title = streamStatus === 'live' ? '合约成交实时推送' : '实时通道连接中，REST 快照备用';
  els.updated.textContent = `${label} · ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}
api.onMarketStatus(status => { streamStatus = status; setStatus(); });
api.getMarketStatus().then(status => { streamStatus = status; setStatus(); });

// ---------- 数据 ----------
// 初始用 REST 拉一次 (秒开), 之后由 WebSocket 实时推送驱动
async function initialFetch() {
  try {
    const tickers = await api.getTickers();
    render(tickers);
    setStatus(tickers.some((t) => t.ok));
  } catch (e) {
    setStatus(false);
  }
}

// ---------- 事件绑定 ----------
document.getElementById('btn-settings').addEventListener('click', () => api.openPanel());
// "—" 收缩为桌面迷你悬浮球
document.getElementById('btn-hide').addEventListener('click', () => api.collapse());
// "✕" 隐藏到系统托盘 (从托盘可恢复; 退出在托盘右键菜单)
document.getElementById('btn-close').addEventListener('click', () => api.hide());

// 主面板修改配置后, 立即同步
api.onConfigChanged((cfg) => {
  config = cfg;
  applyColorScheme(config.colorScheme);
  lastPrices = {};
  initialFetch();
  refreshKlines();
});

// WebSocket 实时推送 -> 直接渲染 (丝滑跳动)
api.onTickersUpdate((list) => {
  render(list);
  setStatus(list.some((t) => t.ok));
});

// ---------- 初始化 ----------
(async function init() {
  config = await api.getConfig();
  applyColorScheme(config.colorScheme);
  initialFetch();
  refreshKlines();
  setInterval(refreshKlines, 30000);
})();
