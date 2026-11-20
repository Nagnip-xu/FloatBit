const WebSocket = require('ws');
const ENDPOINTS = ['wss://fstream.binance.com/market/stream', 'wss://fstream.binance.me/market/stream'];
const streamsFor = symbols => symbols.flatMap(s => [s.toLowerCase() + '@aggTrade', s.toLowerCase() + '@ticker']).join('/');

// Race complete market messages, not just HTTP 101. A healthy stream wins;
// losers and stale generations never publish. All endpoints are Binance futures.
class LiveMarket {
  constructor({ onData, onStatus = () => {}, createSocket = (url, options) => new WebSocket(url, options), endpoints = ENDPOINTS }) {
    Object.assign(this, { onData, onStatus, createSocket, endpoints });
    this.generation = 0; this.symbols = []; this.stopped = true;
    this.candidates = new Set(); this.failures = 0; this.lastMessage = 0;
  }
  start(symbols) {
    this.stop(); this.stopped = false; this.symbols = [...symbols]; this.failures = 0;
    if (symbols.length) this.connect();
  }
  stop() {
    this.stopped = true; this.generation++;
    clearTimeout(this.retry); clearTimeout(this.deadline); clearInterval(this.heartbeat);
    for (const s of this.candidates) { s.removeAllListeners(); s.on('error', () => {}); s.terminate(); }
    this.candidates.clear(); this.winner = null;
  }
  connect() {
    if (this.stopped || !this.symbols.length) return;
    const gen = ++this.generation;
    this.onStatus('connecting');
    this.deadline = setTimeout(() => this.reconnect(gen), 6500);
    const fail = socket => {
      if (gen !== this.generation || this.stopped) return;
      this.candidates.delete(socket);
      if (this.winner === socket || !this.candidates.size) this.reconnect(gen);
    };
    for (const base of this.endpoints) {
      let socket;
      try { socket = this.createSocket(`${base}?streams=${streamsFor(this.symbols)}`, { handshakeTimeout: 4500 }); }
      catch { continue; }
      this.candidates.add(socket);
      socket.on('error', () => { socket.terminate(); fail(socket); });
      socket.on('close', () => fail(socket));
      socket.on('pong', () => { this.lastMessage = Date.now(); });
      socket.on('message', buf => {
        if (this.stopped || gen !== this.generation || (this.winner && this.winner !== socket)) return;
        let data;
        try { data = JSON.parse(buf.toString()).data; } catch { return; }
        if (!data || !this.symbols.includes(data.s) || !['aggTrade', '24hrTicker'].includes(data.e)
          || !(Number(data.e === 'aggTrade' ? data.p : data.c) > 0)) return;
        if (!this.winner) {
          this.winner = socket; this.failures = 0; clearTimeout(this.deadline);
          for (const other of this.candidates) if (other !== socket) {
            other.removeAllListeners(); other.on('error', () => {}); other.terminate();
          }
          this.candidates = new Set([socket]);
          this.heartbeat = setInterval(() => {
            if (Date.now() - this.lastMessage > 12000) return this.reconnect(gen);
            if (socket.readyState === WebSocket.OPEN) socket.ping();
          }, 5000);
        }
        this.lastMessage = Date.now(); this.onStatus('live'); this.onData(data);
      });
    }
    if (!this.candidates.size) this.reconnect(gen);
  }
  reconnect(gen) {
    if (this.stopped || gen !== this.generation) return;
    const symbols = this.symbols;
    const delay = Math.min(500 * 2 ** Math.min(this.failures++, 4), 8000);
    this.stop(); this.symbols = symbols; this.stopped = false;
    this.onStatus('reconnecting');
    this.retry = setTimeout(() => this.connect(), delay);
  }
}

// Preserve the newest exchange trade time when slower ticker/REST messages arrive.
function mergeEvent(previous, d, dayOpen) {
  const trade = d.e === 'aggTrade';
  const last = Number(trade ? d.p : d.c);
  const eventTime = Number(trade ? d.T || d.E : d.C || d.E);
  if (!(last > 0) || !Number.isFinite(eventTime) || eventTime <= 0) return previous;
  const newer = !previous?.eventTime || eventTime >= previous.eventTime;
  const price = newer ? last : previous.last;
  const open = trade ? previous?.open : Number(d.o);
  const anchor = dayOpen || open;
  return { ...previous, symbol: d.s, last: price, open,
    high: trade ? previous?.high : Number(d.h), low: trade ? previous?.low : Number(d.l),
    changePct: anchor > 0 ? (price - anchor) / anchor * 100 : (previous?.changePct ?? Number.NaN),
    eventTime: newer ? eventTime : previous.eventTime, receivedAt: Date.now(), source: 'live', ok: true };
}

function mergeSnapshot(previous, snapshot, requestedAt) {
  if (previous?.ok && (!snapshot.ok || (previous.source === 'live' && previous.receivedAt >= requestedAt)
    || previous.eventTime > snapshot.eventTime)) {
    const open = Number.isFinite(previous.open) ? previous.open : snapshot.open;
    return { ...snapshot, ...previous, open,
      changePct: Number.isFinite(previous.changePct) ? previous.changePct : open > 0 ? (previous.last - open) / open * 100 : snapshot.changePct };
  }
  return snapshot;
}
module.exports = { LiveMarket, streamsFor, mergeEvent, mergeSnapshot, ENDPOINTS };
