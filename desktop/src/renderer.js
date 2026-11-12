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
  // BTCUSDT -> { coin: 'BTC', pair: 'USDT' }
  const quotes = ['USDT', 'USDC', 'USD1', 'USD', 'BTC', 'ETH'];
  for (const q of quotes) {
    if (symbol.endsWith(q) && symbol.length > q.length) {
      return { coin: symbol.slice(0, symbol.length - q.length), pair: q };
    }
  }
  return { coin: symbol, pair: '' };
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
      wrap.innerHTML = buildSparkline(closes, up);
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
function render(tickers) {
  els.list.innerHTML = '';
  for (const t of tickers) {
    if (t.ok && isFinite(t.changePct)) changeMap[t.symbol] = t.changePct;
    const { coin, pair } = coinName(t.symbol);
    const row = document.createElement('div');
    row.className = 'row';

    if (!t.ok) {
      row.innerHTML = `
        <div class="sym">
          <span class="coin">${coin}</span>
          <span class="pair">${pair}</span>
        </div>
        <div class="right">
          <span class="price err">${t.error || '错误'}</span>
        </div>`;
      els.list.appendChild(row);
      continue;
    }

    const up = t.changePct >= 0;
    const prev = lastPrices[t.symbol];
    if (prev !== undefined && t.last !== prev) {
      row.classList.add(t.last > prev ? 'flash-up' : 'flash-down');
    }
    lastPrices[t.symbol] = t.last;

    row.innerHTML = `
      <div class="sym">
        <span class="coin">${coin}</span>
        <span class="pair">${pair}</span>
      </div>
      <div class="spark-wrap" data-spark="${t.symbol}"></div>
      <div class="right">
        <span class="price">${formatPrice(t.last)}</span>
        <span class="chg ${up ? 'up' : 'down'}">${formatPct(t.changePct)}</span>
      </div>`;
    els.list.appendChild(row);
  }

  if (!tickers.length) {
    els.list.innerHTML = '<div class="loading">点击 ⚙ 添加币种</div>';
  }

  // 重新应用缓存中的走势图
  applySparklines();
}

function setStatus(ok) {
  els.status.classList.toggle('off', !ok);
  const d = new Date();
  const weekdays = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];
  const date = `${d.getFullYear()}/${d.getMonth() + 1}/${d.getDate()}`;
  const week = weekdays[d.getDay()];
  const time = `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
  els.updated.textContent = `${date} ${week} ${time}`;
}

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
