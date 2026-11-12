// ===== FloatBit 官网交互 =====

// ---------- 滚动入场动画 ----------
const io = new IntersectionObserver((entries) => {
  entries.forEach((e) => {
    if (e.isIntersecting) {
      e.target.classList.add('in');
      io.unobserve(e.target);
    }
  });
}, { threshold: 0.12 });
document.querySelectorAll('.reveal').forEach((el, i) => {
  el.style.transitionDelay = `${(i % 3) * 80}ms`;
  io.observe(el);
});

// ---------- 导航滚动效果 ----------
const nav = document.getElementById('nav');
const onScroll = () => nav.classList.toggle('scrolled', window.scrollY > 20);
window.addEventListener('scroll', onScroll);
onScroll();

// ---------- 行情数据 (币安公共接口) ----------
const BN = 'https://data-api.binance.vision';
const HERO_SYMBOLS = ['BTCUSDT', 'ETHUSDT', 'SOLUSDT'];
const TAPE_SYMBOLS = ['BTCUSDT', 'ETHUSDT', 'BNBUSDT', 'SOLUSDT', 'XRPUSDT', 'DOGEUSDT', 'ADAUSDT', 'LINKUSDT', 'AVAXUSDT', 'DOTUSDT'];

function fmtPrice(n) {
  if (!isFinite(n)) return '--';
  if (n >= 1000) return n.toLocaleString('en-US', { maximumFractionDigits: 2 });
  if (n >= 1) return n.toFixed(2);
  if (n >= 0.01) return n.toFixed(4);
  return n.toPrecision(4);
}
function fmtPct(p) {
  if (!isFinite(p)) return '';
  return `${p >= 0 ? '+' : ''}${p.toFixed(2)}%`;
}
function coinOf(symbol) {
  return symbol.replace(/USDT$/, '');
}

async function fetchTickers(symbols) {
  // 一次性拉多个: /ticker/24hr?symbols=["BTCUSDT",...]
  const param = encodeURIComponent(JSON.stringify(symbols));
  const res = await fetch(`${BN}/api/v3/ticker/24hr?symbols=${param}`);
  if (!res.ok) throw new Error('网络错误');
  return res.json();
}

async function fetchSparkline(symbol) {
  const res = await fetch(`${BN}/api/v3/klines?symbol=${symbol}&interval=15m&limit=30`);
  if (!res.ok) return [];
  const data = await res.json();
  return data.map((k) => parseFloat(k[4])).filter((n) => isFinite(n));
}

function sparkSVG(closes, up) {
  if (!closes || closes.length < 2) return '';
  const w = 60, h = 26, pad = 2;
  const min = Math.min(...closes), max = Math.max(...closes);
  const range = max - min || 1;
  const n = closes.length;
  const pts = closes.map((c, i) => {
    const x = pad + (i / (n - 1)) * (w - pad * 2);
    const y = pad + (1 - (c - min) / range) * (h - pad * 2);
    return `${x.toFixed(1)},${y.toFixed(1)}`;
  }).join(' ');
  const color = up ? 'var(--up)' : 'var(--down)';
  return `<svg class="mock-spark" viewBox="0 0 ${w} ${h}" preserveAspectRatio="none"><polyline points="${pts}" style="stroke:${color}" /></svg>`;
}

// ---------- 首屏悬浮窗卡片 ----------
const liveList = document.getElementById('live-list');
const liveTime = document.getElementById('live-time');
let sparkCache = {};

async function updateHeroCard() {
  try {
    const tickers = await fetchTickers(HERO_SYMBOLS);
    const bySym = {};
    tickers.forEach((t) => (bySym[t.symbol] = t));

    // 走势图缓存 (较慢, 单独刷新)
    if (Object.keys(sparkCache).length === 0) {
      await Promise.all(HERO_SYMBOLS.map(async (s) => { sparkCache[s] = await fetchSparkline(s); }));
    }

    liveList.innerHTML = HERO_SYMBOLS.map((s) => {
      const t = bySym[s];
      if (!t) return '';
      const last = parseFloat(t.lastPrice);
      const pct = parseFloat(t.priceChangePercent);
      const up = pct >= 0;
      return `
        <div class="mock-row">
          <div class="mock-sym"><b>${coinOf(s)}</b><small>USDT</small></div>
          ${sparkSVG(sparkCache[s], up)}
          <div class="mock-price">
            <b>${fmtPrice(last)}</b>
            <small class="${up ? 'up' : 'down'}">${fmtPct(pct)}</small>
          </div>
        </div>`;
    }).join('');

    const d = new Date();
    const p = (x) => String(x).padStart(2, '0');
    liveTime.textContent = `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
  } catch (e) {
    if (!liveList.querySelector('.mock-row')) {
      liveList.innerHTML = '<div class="mock-loading">行情加载失败，请检查网络</div>';
    }
  }
}

// ---------- 实时滚动条 ----------
const tapeTrack = document.getElementById('tape-track');

async function updateTape() {
  try {
    const tickers = await fetchTickers(TAPE_SYMBOLS);
    const bySym = {};
    tickers.forEach((t) => (bySym[t.symbol] = t));

    const items = TAPE_SYMBOLS.map((s) => {
      const t = bySym[s];
      if (!t) return '';
      const last = parseFloat(t.lastPrice);
      const pct = parseFloat(t.priceChangePercent);
      const up = pct >= 0;
      return `<div class="tape-item">
        <span class="t-sym">${coinOf(s)}</span>
        <span class="t-price">${fmtPrice(last)}</span>
        <span class="t-chg ${up ? 'up' : 'down'}">${fmtPct(pct)}</span>
      </div>`;
    }).join('');
    // 复制一份以实现无缝循环
    tapeTrack.innerHTML = items + items;
  } catch (e) {
    /* 静默失败, 保留上次内容 */
  }
}

// ---------- 启动 ----------
updateHeroCard();
updateTape();
setInterval(updateHeroCard, 3000);
setInterval(updateTape, 5000);
// 走势图每分钟刷新一次
setInterval(() => { sparkCache = {}; }, 60000);

// ---------- Bento 卡片: 光标跟随光斑 ----------
document.querySelectorAll('.bento-card').forEach((card) => {
  card.addEventListener('mousemove', (e) => {
    const r = card.getBoundingClientRect();
    card.style.setProperty('--mx', `${e.clientX - r.left}px`);
    card.style.setProperty('--my', `${e.clientY - r.top}px`);
  });
});

// ---------- Bento 实时价格演示 (复用 BTC 数据) ----------
const bentoPrice = document.getElementById('bento-price');
const bentoChg = document.getElementById('bento-chg');
async function updateBentoLive() {
  try {
    const [t] = await fetchTickers(['BTCUSDT']);
    if (!t) return;
    const last = parseFloat(t.lastPrice);
    const pct = parseFloat(t.priceChangePercent);
    const up = pct >= 0;
    if (bentoPrice) {
      bentoPrice.textContent = fmtPrice(last);
      bentoPrice.classList.remove('ln-flash');
      void bentoPrice.offsetWidth; // 重触发动画
      bentoPrice.classList.add('ln-flash');
    }
    if (bentoChg) {
      bentoChg.textContent = fmtPct(pct);
      bentoChg.className = `ln-chg ${up ? 'up' : 'down'}`;
    }
  } catch (e) { /* 静默 */ }
}
updateBentoLive();
setInterval(updateBentoLive, 3000);

// ---------- 手机版 mockup 实时数据 ----------
const mList = document.getElementById('m-live-list');
const M_SYMBOLS = ['BTCUSDT', 'ETHUSDT', 'SOLUSDT', 'XRPUSDT'];

async function updatePhone() {
  if (!mList) return;
  try {
    const tickers = await fetchTickers(M_SYMBOLS);
    const bySym = {};
    tickers.forEach((t) => (bySym[t.symbol] = t));

    if (Object.keys(sparkCache).length === 0) {
      await Promise.all(HERO_SYMBOLS.map(async (s) => { sparkCache[s] = await fetchSparkline(s); }));
    }
    // 手机版额外币种的走势图按需补充
    await Promise.all(M_SYMBOLS.map(async (s) => {
      if (!sparkCache[s]) sparkCache[s] = await fetchSparkline(s);
    }));

    mList.innerHTML = M_SYMBOLS.map((s) => {
      const t = bySym[s];
      if (!t) return '';
      const last = parseFloat(t.lastPrice);
      const pct = parseFloat(t.priceChangePercent);
      const up = pct >= 0;
      const spark = sparkCache[s] ? sparkSVG(sparkCache[s], up).replace('mock-spark', 'm-spark') : '';
      return `
        <div class="m-row">
          <div class="m-coin">${coinOf(s)}<small>USDT</small></div>
          ${spark}
          <div class="m-right">
            <div class="m-price">${fmtPrice(last)}</div>
            <div class="m-chg ${up ? 'up' : 'down'}">${fmtPct(pct)}</div>
          </div>
        </div>`;
    }).join('');
  } catch (e) {
    if (!mList.querySelector('.m-row')) {
      mList.innerHTML = '<div class="mock-loading">行情加载失败</div>';
    }
  }
}
updatePhone();
setInterval(updatePhone, 3000);
