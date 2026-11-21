const { app, BrowserWindow, ipcMain, Tray, Menu, screen, shell, powerMonitor } = require('electron');
const path = require('path');
const fs = require('fs');
const markets = require('./markets');
const { ContractNetwork } = require('./market-network');
const { LiveMarket, mergeEvent, mergeSnapshot } = require('./live-market');
const { TickerPublisher } = require('./ticker-publisher');
const network = new ContractNetwork();

// ---------- 配置持久化 ----------
const CONFIG_PATH = path.join(app.getPath('userData'), 'floatbit-config.json');

const DEFAULT_CONFIG = {
  symbols: ['BTCUSDT', 'ETHUSDT'],
  opacity: 0.95,
  alwaysOnTop: true,
  theme: 'dark', // 主面板主题: dark | light
  colorScheme: 'green-up', // 涨跌配色: green-up(绿涨红跌) | red-up(红涨绿跌)
  bounds: null, // 记忆窗口位置
  miniBounds: null // 记忆迷你球位置
};

function loadConfig() {
  try {
    const raw = fs.readFileSync(CONFIG_PATH, 'utf-8');
    return { ...DEFAULT_CONFIG, ...JSON.parse(raw) };
  } catch {
    return { ...DEFAULT_CONFIG };
  }
}

function saveConfig(cfg) {
  try {
    fs.writeFileSync(CONFIG_PATH, JSON.stringify(cfg, null, 2), 'utf-8');
  } catch (e) {
    console.error('保存配置失败:', e.message);
  }
}

let config = loadConfig();
let mainWindow = null;
let panelWindow = null;
let miniWindow = null;
let tray = null;
let collapsed = false; // 是否处于迷你球状态

// 交易对列表缓存 (用于主面板的搜索与添加)
let symbolsCache = null;
let symbolsCacheTime = 0;
let symbolsRequest = null;
const sourceCatalogs = {};
let symbolsErrors = [];

// ---------- 币安 USDⓈ-M 合约公共行情，无需密钥 ----------
function marketUrl(id, resource) {
  const { market, symbol } = markets.parse(id);
  return `${markets.sources[market].rest}/${resource}?symbol=${encodeURIComponent(symbol)}`;
}

const netGet = url => network.getJson(url);

// 只加载 USDⓈ-M 合约；请求失败时保留旧列表，并向界面报告错误。
async function fetchSymbols(force = false) {
  const now = Date.now();
  if (!force && symbolsCache && now - symbolsCacheTime < 60000) {
    return { symbols: symbolsCache, errors: symbolsErrors };
  }
  if (symbolsRequest) return symbolsRequest;
  symbolsRequest = (async () => {
    const errors = [];
    await Promise.all(Object.entries(markets.sources).map(async ([market, source]) => {
      try {
        const list = markets.catalog(await netGet(`${source.rest}/exchangeInfo`));
        if (!list.length) throw new Error('交易对列表为空');
        sourceCatalogs[market] = list;
      } catch (e) {
        errors.push(`${source.label}列表更新失败`);
        console.error(`${source.label}列表更新失败:`, e.message);
      }
    }));
    symbolsCache = Object.values(sourceCatalogs).flat();
    symbolsErrors = errors;
    symbolsCacheTime = Date.now();
    return { symbols: symbolsCache, errors };
  })();
  try { return await symbolsRequest; } finally { symbolsRequest = null; }
}

// 获取 K线 收盘价序列 (用于 sparkline 走势图) — 币安 klines
async function fetchKline(symbol, interval = '15m', limit = 30) {
  try {
    const url = `${marketUrl(symbol, 'klines')}&interval=${interval}&limit=${limit}`;
    const json = await netGet(url);
    if (Array.isArray(json)) {
      // 每条: [开始时间, 开, 高, 低, 收, 量, ...]; 取收盘价
      const closes = json.map((k) => parseFloat(k[4])).filter((n) => isFinite(n));
      return { symbol, closes, ok: true };
    }
    return { symbol, closes: [], ok: false };
  } catch (e) {
    return { symbol, closes: [], ok: false };
  }
}

async function fetchKlinesFor(symbols) {
  const results = await Promise.all(symbols.map((s) => fetchKline(s)));
  const map = {};
  results.forEach((r) => (map[r.symbol] = r.closes));
  return map;
}

// ---------- 当日开盘价缓存 (用于"当天涨跌幅", 按 UTC 自然日) ----------
// { symbol: { open: number, dayStart: number } }
const dayOpenCache = {};

// 当前 UTC 自然日的起始毫秒时间戳
function utcDayStart() {
  const now = new Date();
  return Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate());
}

// 获取某交易对的当日(UTC)开盘价; 缓存至当天结束
async function getDayOpen(symbol) {
  const dayStart = utcDayStart();
  const cached = dayOpenCache[symbol];
  if (cached && cached.dayStart === dayStart && isFinite(cached.open)) {
    return cached.open;
  }
  try {
    const url = `${marketUrl(symbol, 'klines')}&interval=1d&limit=1`;
    const json = await netGet(url);
    if (Array.isArray(json) && json.length) {
      const candle = json[json.length - 1];
      const candleStart = parseInt(candle[0], 10);
      const open = parseFloat(candle[1]);
      // 仅当这根日线确实是今天 (UTC) 时才采用
      if (candleStart === dayStart && isFinite(open)) {
        dayOpenCache[symbol] = { open, dayStart };
        return open;
      }
    }
  } catch (e) {
    // 忽略, 回退到 24h 涨跌
  }
  return null;
}

// 价格先显示，日开盘和走势图独立加载，不阻塞首价。
const tickerCache = {};
let marketStatus = 'connecting';
let fallbackTimer = null;
const snapshotRequests = new Map();
function currentTickers() {
  return config.symbols.map(symbol => tickerCache[symbol] || { symbol, ok: false, error: '连接中' });
}
const tickerPublisher = new TickerPublisher(() => {
  const tickers = currentTickers();
  for (const win of [mainWindow, panelWindow]) if (win && !win.isDestroyed()) {
    win.webContents.send('tickers-update', tickers);
  }
});
function scheduleTickerPush() { tickerPublisher.schedule(); }
function publishMarketStatus() {
  for (const win of [mainWindow, panelWindow]) if (win && !win.isDestroyed()) {
    win.webContents.send('market-status', marketStatus);
  }
}
function dayOpenFor(symbol) {
  return dayOpenCache[symbol]?.dayStart === utcDayStart() ? dayOpenCache[symbol].open : null;
}
async function fetchTicker(symbol) {
  if (snapshotRequests.has(symbol)) return snapshotRequests.get(symbol);
  const requestedAt = Date.now();
  const operation = (async () => {
    let snapshot;
    try {
      const d = await netGet(marketUrl(symbol, 'ticker/24hr'));
      const last = Number(d.lastPrice);
      if (!(last > 0)) throw new Error('无数据');
      const anchor = dayOpenFor(symbol) || Number(d.openPrice);
      snapshot = { symbol, last, open: Number(d.openPrice), high: Number(d.highPrice), low: Number(d.lowPrice),
        changePct: anchor > 0 ? (last - anchor) / anchor * 100 : Number(d.priceChangePercent),
        eventTime: Number(d.closeTime) || 0, receivedAt: Date.now(), source: 'snapshot', ok: true };
    } catch (e) {
      snapshot = { symbol, ok: false, error: e.code === -1121 ? '无此合约' : e.status === 429 || e.status === 418 ? '请求限流' : '连接失败' };
    }
    if (config.symbols.includes(symbol)) {
      tickerCache[symbol] = mergeSnapshot(tickerCache[symbol], snapshot, requestedAt);
      scheduleTickerPush();
      return tickerCache[symbol];
    }
  })().finally(() => snapshotRequests.delete(symbol));
  snapshotRequests.set(symbol, operation);
  return operation;
}
function fetchAll() {
  // 每个币种独立发布；一个慢请求不会阻塞其他币种，也不会覆盖较新的推送。
  return Promise.all(config.symbols.filter(s => !tickerCache[s]?.ok || marketStatus !== 'live'
    || Date.now() - tickerCache[s].receivedAt > 15000).map(fetchTicker));
}
const live = new LiveMarket({
  onStatus(status) { if (marketStatus !== status) { marketStatus = status; publishMarketStatus(); } },
  onData(d) {
    const symbol = markets.id(d.s);
    if (!config.symbols.includes(symbol)) return;
    tickerCache[symbol] = { ...mergeEvent(tickerCache[symbol], d, dayOpenFor(symbol)), symbol };
    scheduleTickerPush();
  }
});
function connectWs() {
  live.start(config.symbols.map(s => markets.parse(s).symbol));
  config.symbols.forEach(s => {
    getDayOpen(s).then(open => {
      if (open && tickerCache[s]?.ok) {
        tickerCache[s].changePct = (tickerCache[s].last - open) / open * 100;
        scheduleTickerPush();
      }
    }).catch(() => {});
  });
}
function resubscribeWs() {
  for (const s of Object.keys(tickerCache)) if (!config.symbols.includes(s)) delete tickerCache[s];
  connectWs(); fetchAll().catch(() => {});
}

// ---------- 窗口 ----------
function createWindow() {
  const primary = screen.getPrimaryDisplay().workArea;
  const defaultBounds = {
    width: 260,
    height: 160,
    x: primary.x + primary.width - 280,
    y: primary.y + 20
  };
  const bounds = config.bounds || defaultBounds;

  mainWindow = new BrowserWindow({
    ...bounds,
    minWidth: 230,
    minHeight: 90,
    frame: false,
    transparent: true,
    resizable: true,
    alwaysOnTop: config.alwaysOnTop,
    skipTaskbar: true,
    hasShadow: false,
    fullscreenable: false,
    icon: path.join(__dirname, '..', 'assets', 'icon.ico'),
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false
    }
  });

  mainWindow.setOpacity(config.opacity);
  if (config.alwaysOnTop) {
    mainWindow.setAlwaysOnTop(true, 'floating');
  }
  mainWindow.loadFile(path.join(__dirname, 'index.html'));

  // 记忆窗口位置
  const persistBounds = () => {
    if (!mainWindow) return;
    config.bounds = mainWindow.getBounds();
    saveConfig(config);
  };
  mainWindow.on('moved', persistBounds);
  mainWindow.on('resized', persistBounds);
}

// ---------- 迷你悬浮球 ----------
const MINI_SIZE = 56;

// 预创建迷你球窗口 (复用, 避免每次收起/展开都重建造成卡顿)
function ensureMini() {
  if (miniWindow) return;
  const wa = screen.getPrimaryDisplay().workArea;
  let x = wa.x + wa.width - MINI_SIZE - 20;
  let y = wa.y + 20;
  if (config.miniBounds) { x = config.miniBounds.x; y = config.miniBounds.y; }

  miniWindow = new BrowserWindow({
    width: MINI_SIZE,
    height: MINI_SIZE,
    x,
    y,
    show: false,
    frame: false,
    transparent: true,
    resizable: false,
    alwaysOnTop: true,
    skipTaskbar: true,
    hasShadow: false,
    fullscreenable: false,
    icon: path.join(__dirname, '..', 'assets', 'icon.ico'),
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false
    }
  });
  miniWindow.setAlwaysOnTop(true, 'floating');
  miniWindow.loadFile(path.join(__dirname, 'mini.html'));

  const persistMini = () => {
    if (!miniWindow) return;
    const b = miniWindow.getBounds();
    config.miniBounds = { x: b.x, y: b.y };
    // 球移动时, 同步把(隐藏的)主窗口移到对应位置, 展开时即可零晃动直接显示
    syncMainToMini();
    saveConfig(config);
  };
  miniWindow.on('moved', persistMini);
}

// 根据球的位置, 把主窗口定位到对应位置 (球在窗口右上角)
function syncMainToMini() {
  if (!miniWindow || !mainWindow) return;
  const m = miniWindow.getBounds();
  const w = mainWindow.getBounds();
  let nx = m.x + MINI_SIZE - w.width;
  let ny = m.y;
  const wa = screen.getDisplayMatching(m).workArea;
  nx = Math.max(wa.x, Math.min(nx, wa.x + wa.width - w.width));
  ny = Math.max(wa.y, Math.min(ny, wa.y + wa.height - w.height));
  mainWindow.setBounds({ x: Math.round(nx), y: Math.round(ny), width: w.width, height: w.height });
  config.bounds = mainWindow.getBounds();
}

// 折叠: 隐藏主悬浮窗, 显示迷你球
function collapseToMini() {
  ensureMini();
  if (mainWindow && miniWindow) {
    const b = mainWindow.getBounds();
    miniWindow.setBounds({
      x: b.x + b.width - MINI_SIZE,
      y: b.y,
      width: MINI_SIZE,
      height: MINI_SIZE
    });
  }
  if (miniWindow) miniWindow.showInactive();
  if (mainWindow) {
    mainWindow.setOpacity(0);
    mainWindow.setIgnoreMouseEvents(true);
  }
  collapsed = true;
}

// 展开: 主窗口已随球同步好位置, 仅恢复透明度即可 (零 show/hide, 零晃动)
function expandFromMini() {
  if (!collapsed) return;
  // 主窗口此刻 opacity=0, 移动它没有任何视觉效果, 先对齐到球的位置
  syncMainToMini();
  saveConfig(config);
  if (miniWindow) miniWindow.hide();
  if (mainWindow) {
    mainWindow.setIgnoreMouseEvents(false);
    mainWindow.setOpacity(config.opacity);
    if (config.alwaysOnTop) mainWindow.setAlwaysOnTop(true, 'floating');
  }
  collapsed = false;
}

// 统一: 把悬浮窗恢复到正常可见状态 (无论当前是迷你球还是隐藏到托盘)
function showFloating() {
  if (collapsed) { expandFromMini(); return; }
  if (mainWindow) {
    mainWindow.setIgnoreMouseEvents(false);
    mainWindow.setOpacity(config.opacity);
    if (!mainWindow.isVisible()) mainWindow.show();
    if (config.alwaysOnTop) mainWindow.setAlwaysOnTop(true, 'floating');
  }
}

// 统一: 隐藏到托盘 (兼顾迷你球状态)
function hideFloating() {
  if (collapsed) {
    if (miniWindow) miniWindow.hide();
    collapsed = false;
    if (mainWindow) {
      mainWindow.setIgnoreMouseEvents(false);
      mainWindow.setOpacity(config.opacity);
    }
  }
  if (mainWindow) mainWindow.hide();
}

// 是否处于"对用户可见"状态
function isFloatingVisible() {
  return !collapsed && mainWindow && mainWindow.isVisible();
}

// ---------- 主面板窗口 ----------
function createPanel() {
  if (panelWindow) {
    panelWindow.show();
    panelWindow.focus();
    return;
  }

  panelWindow = new BrowserWindow({
    width: 460,
    height: 600,
    minWidth: 400,
    minHeight: 480,
    title: 'FloatBit',
    frame: false,
    transparent: false,
    backgroundColor: '#12141a',
    resizable: true,
    show: false,
    skipTaskbar: false,
    icon: path.join(__dirname, '..', 'assets', 'icon.ico'),
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false
    }
  });

  panelWindow.loadFile(path.join(__dirname, 'panel.html'));
  panelWindow.once('ready-to-show', () => panelWindow.show());
  panelWindow.on('closed', () => {
    panelWindow = null;
  });
}

// 通知悬浮窗配置已更新 (立即刷新)
function notifyConfigChanged() {
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send('config-changed', config);
  }
}

// ---------- 系统托盘 ----------
function createTray() {
  // 使用生成的图标文件
  const { nativeImage } = require('electron');
  const icon = nativeImage.createFromPath(path.join(__dirname, '..', 'assets', 'tray.png'));
  tray = new Tray(icon);
  tray.setToolTip('FloatBit · 加密货币悬浮窗');

  const buildMenu = () =>
    Menu.buildFromTemplate([
      {
        label: '主面板 / 添加币种',
        click: () => createPanel()
      },
      {
        label: '显示 / 隐藏悬浮窗',
        click: () => {
          isFloatingVisible() ? hideFloating() : showFloating();
        }
      },
      {
        label: '窗口置顶',
        type: 'checkbox',
        checked: config.alwaysOnTop,
        click: (item) => {
          config.alwaysOnTop = item.checked;
          if (mainWindow) mainWindow.setAlwaysOnTop(item.checked, 'floating');
          saveConfig(config);
        }
      },
      {
        label: '不透明度',
        submenu: [100, 90, 80, 70, 60, 50].map((p) => ({
          label: `${p}%`,
          type: 'radio',
          checked: Math.round(config.opacity * 100) === p,
          click: () => {
            config.opacity = p / 100;
            if (mainWindow) mainWindow.setOpacity(config.opacity);
            saveConfig(config);
          }
        }))
      },
      { type: 'separator' },
      {
        label: 'API 文档',
        click: () => shell.openExternal('https://developers.binance.com/docs/derivatives/usds-margined-futures/market-data/rest-api/Exchange-Information')
      },
      { type: 'separator' },
      { label: '退出 FloatBit', click: () => app.quit() }
    ]);

  tray.setContextMenu(buildMenu());
  tray.on('click', () => {
    isFloatingVisible() ? hideFloating() : showFloating();
  });
}

// ---------- IPC ----------
ipcMain.handle('get-tickers', () => { fetchAll().catch(() => {}); return currentTickers(); });
ipcMain.handle('get-market-status', () => marketStatus);

ipcMain.handle('get-config', () => config);

ipcMain.handle('get-symbols', async (_evt, force) => fetchSymbols(force === true));

ipcMain.handle('get-klines', async (_evt, symbols) => fetchKlinesFor(symbols || []));

ipcMain.handle('set-config', (_evt, patch) => {
  const prevSymbols = [...config.symbols].sort().join(',');
  config = { ...config, ...patch };
  saveConfig(config);
  if (mainWindow) {
    // 折叠(迷你球)状态下不要改透明度, 否则会让隐藏的主窗口显形
    if (typeof patch.opacity === 'number' && !collapsed) mainWindow.setOpacity(patch.opacity);
    if (typeof patch.alwaysOnTop === 'boolean')
      mainWindow.setAlwaysOnTop(patch.alwaysOnTop, 'floating');
  }
  // 自选变化 -> 重新订阅 WebSocket
  if (Array.isArray(patch.symbols) && [...patch.symbols].sort().join(',') !== prevSymbols) {
    resubscribeWs();
  }
  notifyConfigChanged();
  return config;
});

ipcMain.on('open-panel', () => createPanel());

ipcMain.on('open-external', (_evt, url) => {
  if (typeof url === 'string' && (url.startsWith('mailto:') || url.startsWith('https://'))) {
    shell.openExternal(url);
  }
});

ipcMain.on('show-floating', () => {
  showFloating();
});

ipcMain.on('window-close', () => app.quit());
ipcMain.on('window-hide', () => hideFloating());
ipcMain.on('window-collapse', () => collapseToMini());
ipcMain.on('mini-expand', () => expandFromMini());
ipcMain.on('mini-move', (_evt, dx, dy) => {
  if (!miniWindow) return;
  const b = miniWindow.getBounds();
  miniWindow.setBounds({ x: Math.round(b.x + dx), y: Math.round(b.y + dy), width: b.width, height: b.height });
});
ipcMain.on('panel-close', () => panelWindow && panelWindow.close());
ipcMain.on('panel-minimize', () => panelWindow && panelWindow.minimize());

// ---------- 生命周期 ----------
// 单实例锁
const gotLock = app.requestSingleInstanceLock();
if (!gotLock) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (mainWindow) {
      mainWindow.show();
      mainWindow.focus();
    }
  });

  app.whenReady().then(() => {
    createWindow();
    createTray();
    // 预创建迷你球 (隐藏待命), 首次收起/展开即时响应
    ensureMini();
    // 启动 WebSocket 实时行情
    connectWs();
    fetchAll().catch(() => {});
    // REST 只负责补齐首值/断线；健康的实时连接不靠轮询驱动价格。
    fallbackTimer = setInterval(() => fetchAll().catch(() => {}), 5000);
    powerMonitor.on('resume', () => { connectWs(); fetchAll().catch(() => {}); });
    app.on('activate', () => {
      if (BrowserWindow.getAllWindows().length === 0) createWindow();
    });
  });

  // 悬浮窗常驻: 关闭所有窗口不退出 (靠托盘控制)
  app.on('window-all-closed', () => {
    // 保持后台运行; 退出走托盘菜单
  });
}

app.on('before-quit', () => { live.stop(); tickerPublisher.stop(); clearInterval(fallbackTimer); });
