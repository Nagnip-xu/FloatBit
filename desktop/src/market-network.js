const https = require('node:https');
const REST = 'https://fapi.binance.com/fapi/v1';
const agents = new Map();
function agentFor(host) {
  if (!agents.has(host)) agents.set(host, new https.Agent({ keepAlive: true }));
  return agents.get(host);
}
class HttpError extends Error { constructor(status, data) { super(data?.msg || `HTTP ${status}`); this.status = status; this.code = data?.code; } }
function requestJson(url, { host, signal, timeout = 4500 } = {}) {
  return new Promise((resolve, reject) => {
    const target = new URL(url);
    // Node otherwise derives TLS SNI from the overridden Host header. TLS must
    // address/verify the CDN hostname while HTTP routes to the Binance origin.
    const req = https.get(target, { agent: agentFor(target.hostname), signal, servername: target.hostname,
      headers: { Accept: 'application/json', ...(host ? { Host: host } : {}) } }, res => {
      let body = '';
      res.setEncoding('utf8');
      res.on('error', reject); res.on('data', data => { body += data; if (body.length > 16000000) req.destroy(new Error('响应过大')); });
      res.on('end', () => {
        try {
          const data = JSON.parse(body);
          if (res.statusCode < 200 || res.statusCode >= 300) reject(new HttpError(res.statusCode, data));
          else if (!data || typeof data !== 'object') reject(new Error('行情数据异常'));
          else resolve(data);
        } catch (e) { reject(e); }
      });
    });
    const timer = setTimeout(() => req.destroy(new Error('连接超时')), timeout);
    req.on('close', () => clearTimeout(timer)); req.on('error', reject);
  });
}
function parseCname(data) {
  if (data.Status !== 0) return null;
  const answer = data.Answer?.find(a => a.type === 5 && a.name.replace(/\.$/, '') === 'fapi.binance.com'
    && /^[a-z0-9]+\.cloudfront\.net\.?$/.test(a.data));
  return answer ? { base: `https://${answer.data.replace(/\.$/, '')}/fapi/v1`, ttl: Math.max(30, Math.min(answer.TTL || 60, 300)) * 1000 } : null;
}
class ContractNetwork {
  constructor({ get = requestJson, resolveCdn, onRoute = () => {} } = {}) {
    this.get = get; this.onRoute = onRoute; this.inFlight = new Map();
    this.resolveCdn = resolveCdn || (async () => parseCname(await get('https://doh.pub/dns-query?name=fapi.binance.com&type=CNAME', { timeout: 2500 })));
  }
  async cdn() {
    if (this.route && this.route.expires > Date.now()) return this.route.base;
    if (!this.lookup) this.lookup = this.resolveCdn().then(answer => {
      if (answer) { this.route = { base: answer.base, expires: Date.now() + answer.ttl }; return answer.base; }
    }).finally(() => { this.lookup = null; });
    return this.lookup;
  }
  getJson(url) {
    if (!url.startsWith(REST + '/')) return this.get(url);
    if (this.inFlight.has(url)) return this.inFlight.get(url);
    const operation = this.race(url).finally(() => this.inFlight.delete(url));
    this.inFlight.set(url, operation); return operation;
  }
  race(url) {
    const suffix = url.slice(REST.length);
    return new Promise((resolve, reject) => {
      const controller = new AbortController(); let done = false, failed = 0, lastError;
      const finish = (error, value, route) => {
        if (done) return;
        if (!error) { done = true; controller.abort(); this.onRoute(route); resolve(value); }
        else {
          if (error instanceof HttpError && error.status >= 400 && error.status < 500) { done = true; controller.abort(); reject(error); return; }
          lastError = error;
          if (++failed === 2) { done = true; controller.abort(); reject(lastError); }
        }
      };
      this.get(url, { signal: controller.signal }).then(d => finish(null, d, 'primary'), e => finish(e));
      // Resolve in parallel with the primary; never wait for a blocked host's timeout.
      this.cdn().then(base => {
        if (!base) throw new Error('备用通道解析失败');
        if (done) return;
        return this.get(base + suffix, { host: 'fapi.binance.com', signal: controller.signal })
          .then(d => finish(null, d, 'cdn'));
      }).catch(e => finish(e));
    });
  }
}
module.exports = { ContractNetwork, requestJson, parseCname, HttpError, REST };
