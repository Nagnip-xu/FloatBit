const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('floatbit', {
  // 数据
  getTickers: () => ipcRenderer.invoke('get-tickers'),
  getMarketStatus: () => ipcRenderer.invoke('get-market-status'),
  onMarketStatus: (cb) => ipcRenderer.on('market-status', (_e, status) => cb(status)),
  getConfig: () => ipcRenderer.invoke('get-config'),
  getSymbols: (force = false) => ipcRenderer.invoke('get-symbols', force),
  getKlines: (symbols) => ipcRenderer.invoke('get-klines', symbols),
  setConfig: (patch) => ipcRenderer.invoke('set-config', patch),

  // 悬浮窗控制
  openPanel: () => ipcRenderer.send('open-panel'),
  showFloating: () => ipcRenderer.send('show-floating'),
  hide: () => ipcRenderer.send('window-hide'),
  collapse: () => ipcRenderer.send('window-collapse'),
  expand: () => ipcRenderer.send('mini-expand'),
  miniMove: (dx, dy) => ipcRenderer.send('mini-move', dx, dy),
  quit: () => ipcRenderer.send('window-close'),

  // 主面板控制
  panelClose: () => ipcRenderer.send('panel-close'),
  panelMinimize: () => ipcRenderer.send('panel-minimize'),
  openExternal: (url) => ipcRenderer.send('open-external', url),

  // 监听配置变化 (悬浮窗用)
  onConfigChanged: (cb) => ipcRenderer.on('config-changed', (_e, cfg) => cb(cfg)),
  // 监听实时行情推送 (WebSocket)
  onTickersUpdate: (cb) => ipcRenderer.on('tickers-update', (_e, list) => cb(list))
});
