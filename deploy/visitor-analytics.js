const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const DAY_MS = 24 * 60 * 60 * 1000;
const SESSION_MS = 30 * 60 * 1000;
const ONLINE_MS = 5 * 60 * 1000;

function parseCookies(header = '') {
  return Object.fromEntries(String(header).split(';').map(part => {
    const index = part.indexOf('=');
    if (index < 0) return ['', ''];
    return [part.slice(0, index).trim(), decodeURIComponent(part.slice(index + 1).trim())];
  }).filter(([key]) => key));
}

function classifyAgent(value = '') {
  const ua = String(value);
  const browser = /Edg\//.test(ua) ? 'Edge' : /OPR\//.test(ua) ? 'Opera' : /SamsungBrowser\//.test(ua) ? 'Samsung Internet' : /Chrome\//.test(ua) ? 'Chrome' : /Firefox\//.test(ua) ? 'Firefox' : /Safari\//.test(ua) ? 'Safari' : '기타';
  const device = /iPad|Tablet/i.test(ua) ? '태블릿' : /Mobile|Android|iPhone/i.test(ua) ? '모바일' : 'PC';
  const os = /Android/i.test(ua) ? 'Android' : /iPhone|iPad|iPod/i.test(ua) ? 'iOS' : /Windows/i.test(ua) ? 'Windows' : /Mac OS/i.test(ua) ? 'macOS' : /Linux/i.test(ua) ? 'Linux' : '기타';
  return { browser, device, os };
}

function maskIp(value = '') {
  let ip = String(value).split(',')[0].trim().replace(/^::ffff:/, '');
  if (/^\d{1,3}(\.\d{1,3}){3}$/.test(ip)) {
    const parts = ip.split('.');
    return `${parts[0]}.${parts[1]}.${parts[2]}.xxx`;
  }
  if (ip.includes(':')) {
    const parts = ip.split(':').filter(Boolean);
    return `${parts.slice(0, 3).join(':')}:xxxx`;
  }
  return '확인 불가';
}

function startVisitorAnalytics(app, options = {}) {
  const dataDir = options.dataDir || path.join(__dirname, 'data');
  const eventFile = path.join(dataDir, 'visitor-events.jsonl');
  const adminPassword = String(process.env.ANALYTICS_ADMIN_PASSWORD || '');
  const memory = [];
  fs.mkdirSync(dataDir, { recursive: true });
  if (fs.existsSync(eventFile)) {
    for (const line of fs.readFileSync(eventFile, 'utf8').split('\n')) {
      if (!line.trim()) continue;
      try {
        const event = JSON.parse(line);
        if (!String(event.path || '').startsWith('/api/')) memory.push(event);
      } catch {}
    }
  }

  function isAdmin(req) {
    if (!adminPassword) return false;
    const supplied = String(req.get('x-analytics-password') || '');
    const a = Buffer.from(supplied);
    const b = Buffer.from(adminPassword);
    return a.length === b.length && crypto.timingSafeEqual(a, b);
  }

  function requireAdmin(req, res, next) {
    if (isAdmin(req)) return next();
    res.status(401).json({ error: '관리자 비밀번호를 확인해 주세요.' });
  }

  app.use((req, res, next) => {
    if (req.method !== 'GET' || req.path.startsWith('/analytics') || req.path.startsWith('/api/') || /\.[a-z0-9]{2,5}$/i.test(req.path)) return next();
    const ua = req.get('user-agent') || '';
    if (/bot|crawler|spider|preview|curl|wget|headless/i.test(ua)) return next();
    const cookies = parseCookies(req.headers.cookie);
    let visitorId = /^[a-f0-9]{24}$/.test(cookies.kv) ? cookies.kv : '';
    if (!visitorId) {
      visitorId = crypto.randomBytes(12).toString('hex');
      res.append('Set-Cookie', `kv=${visitorId}; Path=/; Max-Age=31536000; SameSite=Lax; HttpOnly`);
    }
    const previous = [...memory].reverse().find(event => event.visitorId === visitorId);
    const now = Date.now();
    const event = {
      visitorId,
      at: now,
      path: req.path,
      referrer: String(req.get('referer') || '').slice(0, 300),
      maskedIp: maskIp(req.get('x-forwarded-for') || req.socket.remoteAddress),
      ...classifyAgent(ua),
      newSession: !previous || now - previous.at > SESSION_MS
    };
    memory.push(event);
    fs.appendFile(eventFile, `${JSON.stringify(event)}\n`, error => error && console.error('Analytics write failed:', error.message));
    next();
  });

  app.get('/analytics', (req, res) => {
    res.redirect(302, 'https://minohlee.mooo.com/kidsnote/admin');
  });

  app.get('/api/analytics/summary', requireAdmin, (req, res) => {
    const now = Date.now();
    const days = Math.min(90, Math.max(7, Number(req.query.days) || 30));
    const start = now - days * DAY_MS;
    const localDate = timestamp => new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Seoul' }).format(new Date(timestamp));
    const unique = new Set(memory.map(item => item.visitorId));
    const todayKey = localDate(now);
    const today = memory.filter(item => localDate(item.at) === todayKey);
    const recent = memory.filter(item => item.at >= start);
    const latestByVisitor = new Map();
    for (const event of memory) latestByVisitor.set(event.visitorId, event);
    const countBy = (items, key) => Object.entries(items.reduce((result, item) => {
      result[item[key] || '기타'] = (result[item[key] || '기타'] || 0) + 1;
      return result;
    }, {})).map(([name, value]) => ({ name, value })).sort((a, b) => b.value - a.value);
    const dailyMap = new Map();
    for (let offset = days - 1; offset >= 0; offset--) {
      const date = localDate(now - offset * DAY_MS);
      dailyMap.set(date, { date, visitors: new Set(), views: 0, sessions: 0 });
    }
    for (const event of recent) {
      const row = dailyMap.get(localDate(event.at));
      if (!row) continue;
      row.visitors.add(event.visitorId); row.views++; if (event.newSession) row.sessions++;
    }
    res.setHeader('Cache-Control', 'no-store');
    res.json({
      generatedAt: now,
      totals: {
        visitors: unique.size,
        pageViews: memory.length,
        todayVisitors: new Set(today.map(item => item.visitorId)).size,
        todayViews: today.length,
        online: [...latestByVisitor.values()].filter(item => now - item.at <= ONLINE_MS).length
      },
      daily: [...dailyMap.values()].map(row => ({ ...row, visitors: row.visitors.size })),
      devices: countBy(recent, 'device'), browsers: countBy(recent, 'browser'),
      recentVisitors: [...latestByVisitor.values()].sort((a, b) => b.at - a.at).slice(0, 100).map(item => ({
        id: item.maskedIp || `기존 익명 ${item.visitorId.slice(0, 6).toUpperCase()}`, lastSeen: item.at, path: item.path,
        device: item.device, browser: item.browser, os: item.os, online: now - item.at <= ONLINE_MS
      }))
    });
  });
}

module.exports = { startVisitorAnalytics };
