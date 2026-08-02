require('dotenv').config();
const express = require('express');
const cors = require('cors');
const morgan = require('morgan');
const path = require('path');
const fs = require('fs');
const crypto = require('crypto');
const https = require('https');
const { execFile } = require('child_process');
const { promisify } = require('util');
const archiver = require('archiver');

const execFileAsync = promisify(execFile);
const app = express();
const PORT = Number(process.env.PORT) || 3100;
const KIDSNOTE_SESSION_SECRET = process.env.KIDSNOTE_SESSION_SECRET || '';
const KIDSNOTE_SESSION_COOKIE = 'kidsnote_photo_session';
const KIDSNOTE_SESSION_TTL_MS = 30 * 24 * 60 * 60 * 1000;
const KIDSNOTE_SESSION_FILE = path.join(__dirname, 'data', 'kidsnote-sessions.json');
const CHROMIUM_EXECUTABLE = process.env.CHROMIUM_EXECUTABLE || 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const PHOTO_BACKUP_DIR = process.env.PHOTO_BACKUP_DIR
  ? path.resolve(process.env.PHOTO_BACKUP_DIR)
  : path.join(__dirname, 'data', 'photo-backups');
const PHOTO_FILES_DIR = path.join(PHOTO_BACKUP_DIR, 'files');
const PHOTO_THUMBS_DIR = path.join(PHOTO_BACKUP_DIR, 'thumbs');
const PHOTO_INDEX_FILE = path.join(PHOTO_BACKUP_DIR, 'photos.json');
const photoBackupJobs = new Map();
const thumbnailQueue = [];
let activeThumbnailJobs = 0;
const MAX_THUMBNAIL_JOBS = 2;

app.use(cors());
app.use(morgan('dev'));
app.use(express.json({ limit: '1mb' }));
app.use(express.urlencoded({ limit: '1mb', extended: true }));
app.use(express.static(path.join(__dirname, 'public'), { etag: true, maxAge: '1h' }));

function ensurePhotoBackupStore() {
  fs.mkdirSync(PHOTO_FILES_DIR, { recursive: true });
  fs.mkdirSync(PHOTO_THUMBS_DIR, { recursive: true });
  if (!fs.existsSync(PHOTO_INDEX_FILE)) {
    fs.writeFileSync(PHOTO_INDEX_FILE, '[]\n', 'utf8');
  }
}

function readPhotoIndex() {
  try {
    ensurePhotoBackupStore();
    const photos = JSON.parse(fs.readFileSync(PHOTO_INDEX_FILE, 'utf8'));
    return Array.isArray(photos) ? photos : [];
  } catch (error) {
    console.error('Failed to read photo backup index:', error.message);
    return [];
  }
}

function writePhotoIndex(photos) {
  ensurePhotoBackupStore();
  fs.writeFileSync(PHOTO_INDEX_FILE, `${JSON.stringify(photos, null, 2)}\n`, 'utf8');
}

function getPhotoById(id) {
  const photos = readPhotoIndex();
  return { photos, photo: photos.find(item => item.id === id) };
}

function getPhotoFilePath(photo) {
  const filePath = path.resolve(PHOTO_FILES_DIR, photo?.filename || '');
  const root = path.resolve(PHOTO_FILES_DIR) + path.sep;
  return filePath.startsWith(root) ? filePath : '';
}

function getPhotoThumbPath(photo) {
  const thumbPath = path.resolve(PHOTO_THUMBS_DIR, `${photo?.id || ''}.jpg`);
  const root = path.resolve(PHOTO_THUMBS_DIR) + path.sep;
  return thumbPath.startsWith(root) ? thumbPath : '';
}

function runThumbnailTask(task) {
  return new Promise((resolve, reject) => {
    thumbnailQueue.push({ task, resolve, reject });
    drainThumbnailQueue();
  });
}

function drainThumbnailQueue() {
  while (activeThumbnailJobs < MAX_THUMBNAIL_JOBS && thumbnailQueue.length) {
    const item = thumbnailQueue.shift();
    activeThumbnailJobs++;
    item.task()
      .then(item.resolve, item.reject)
      .finally(() => {
        activeThumbnailJobs--;
        drainThumbnailQueue();
      });
  }
}

async function ensurePhotoThumbnail(photo) {
  ensurePhotoBackupStore();
  const sourcePath = getPhotoFilePath(photo);
  const thumbPath = getPhotoThumbPath(photo);
  if (!sourcePath || !thumbPath || !fs.existsSync(sourcePath)) return '';
  if (fs.existsSync(thumbPath)) return thumbPath;

  const temporaryPath = `${thumbPath}.${process.pid}-${Date.now()}.tmp.jpg`;
  try {
    await runThumbnailTask(() => execFileAsync('ffmpeg', [
      '-y',
      '-hide_banner',
      '-loglevel', 'error',
      '-i', sourcePath,
      '-vf', 'scale=360:360:force_original_aspect_ratio=increase,crop=360:360',
      '-frames:v', '1',
      '-q:v', '5',
      temporaryPath
    ], { timeout: 30000 }));
    fs.renameSync(temporaryPath, thumbPath);
    return thumbPath;
  } catch (error) {
    if (fs.existsSync(temporaryPath)) fs.rmSync(temporaryPath, { force: true });
    console.warn('Photo thumbnail generation failed:', photo?.id, error.message);
    return '';
  }
}

function sanitizePhotoName(value) {
  const base = path.basename(String(value || 'photo')).replace(/[<>:"/\\|?*\x00-\x1F]/g, '_').trim();
  return base || 'photo';
}

function getImageExtension(contentType, sourceUrl = '') {
  const type = String(contentType || '').toLowerCase();
  if (type.includes('jpeg') || type.includes('jpg')) return '.jpg';
  if (type.includes('png')) return '.png';
  if (type.includes('webp')) return '.webp';
  if (type.includes('gif')) return '.gif';
  if (type.includes('heic')) return '.heic';
  const ext = path.extname(new URL(sourceUrl).pathname).toLowerCase();
  return /^\.(jpe?g|png|webp|gif|heic)$/.test(ext) ? ext : '.jpg';
}

function getPhotoSourceId(sourceUrl) {
  return crypto.createHash('sha256').update(String(sourceUrl)).digest('hex');
}

function getKidsNoteImageKey(sourceUrl) {
  try {
    const url = new URL(sourceUrl);
    const host = url.hostname.toLowerCase();
    const parts = url.pathname.split('/').filter(Boolean);
    if (host === 'up-kids-kage.kakao.com' && parts[0] === 'dn' && parts.length >= 5) {
      return `${host}/${parts.slice(0, -1).join('/')}`;
    }
  } catch {}
  return '';
}

function getKidsNoteImageQuality(sourceUrl, size = 0) {
  let filename = '';
  try {
    filename = path.basename(new URL(sourceUrl).pathname).toLowerCase();
  } catch {}
  let score = 50;
  if (/^(img|image|photo)\.(jpe?g|png|webp|gif|heic)$/i.test(filename)) score = 100;
  else if (/_l\.(jpe?g|png|webp|gif|heic)$/i.test(filename) || /large/i.test(filename)) score = 80;
  else if (/(_240x240|small|thumb|thumbnail|pre\d*_small)/i.test(filename)) score = 10;
  return score * 10000000000 + (Number(size) || 0);
}

function getExistingPhotoUrlSet(photos = readPhotoIndex()) {
  return new Set(photos.map(photo => photo.sourceUrl).filter(Boolean));
}

function buildExistingPhotoUrlMap(photos = readPhotoIndex()) {
  return new Map(photos.map(photo => [photo.sourceUrl, photo]).filter(([sourceUrl]) => Boolean(sourceUrl)));
}

function shouldUpdatePhotoMeta(photo, meta = {}) {
  if (!photo) return false;
  return (!photo.takenAt && Boolean(meta.sourceDate)) ||
    (!photo.sourceTitle && Boolean(meta.sourceTitle)) ||
    (!photo.sourceType && Boolean(meta.sourceType)) ||
    (!photo.sourcePage && Boolean(meta.sourcePage));
}

function updateExistingPhotoMeta(sourceUrl, meta = {}) {
  const photos = readPhotoIndex();
  const index = photos.findIndex(photo => photo.sourceUrl === sourceUrl);
  if (index === -1) return false;
  const photo = photos[index];
  const next = {
    ...photo,
    takenAt: photo.takenAt || meta.sourceDate || '',
    sourceTitle: photo.sourceTitle || meta.sourceTitle || '',
    sourceType: photo.sourceType || meta.sourceType || '',
    sourcePage: photo.sourcePage || meta.sourcePage || ''
  };
  if (JSON.stringify(next) === JSON.stringify(photo)) return false;
  photos[index] = next;
  writePhotoIndex(photos);
  return true;
}

function updateExistingPhotoMetaByImageKey(sourceUrl, meta = {}) {
  const imageKey = getKidsNoteImageKey(sourceUrl);
  if (!imageKey) return 0;
  const photos = readPhotoIndex();
  let changed = 0;
  const updated = photos.map(photo => {
    const currentKey = photo.imageKey || getKidsNoteImageKey(photo.sourceUrl);
    if (currentKey !== imageKey) return photo;
    const next = {
      ...photo,
      imageKey,
      takenAt: photo.takenAt || meta.sourceDate || '',
      sourceTitle: photo.sourceTitle || meta.sourceTitle || '',
      sourceType: photo.sourceType || meta.sourceType || '',
      sourcePage: photo.sourcePage || meta.sourcePage || ''
    };
    if (JSON.stringify(next) !== JSON.stringify(photo)) changed++;
    return next;
  });
  if (changed) writePhotoIndex(updated);
  return changed;
}

function updateExistingPhotoMetaBySourcePage(sourcePage, meta = {}) {
  if (!sourcePage) return 0;
  const photos = readPhotoIndex();
  let changed = 0;
  const updated = photos.map(photo => {
    if (photo.sourcePage !== sourcePage) return photo;
    const next = {
      ...photo,
      takenAt: photo.takenAt || meta.sourceDate || '',
      sourceTitle: photo.sourceTitle || meta.sourceTitle || '',
      sourceType: photo.sourceType || meta.sourceType || ''
    };
    if (JSON.stringify(next) !== JSON.stringify(photo)) changed++;
    return next;
  });
  if (changed) writePhotoIndex(updated);
  return changed;
}

function addBackedUpPhoto({ sourceUrl, buffer, mimeType, sourcePage, sourceType, sourceDate, sourceTitle }) {
  if (!Buffer.isBuffer(buffer) || buffer.length < 8 * 1024) return null;
  const photos = readPhotoIndex();
  if (photos.some(photo => photo.sourceUrl === sourceUrl)) return null;
  const imageKey = getKidsNoteImageKey(sourceUrl);
  if (imageKey) {
    const duplicates = photos
      .map((photo, index) => ({ photo, index, imageKey: photo.imageKey || getKidsNoteImageKey(photo.sourceUrl) }))
      .filter(item => item.imageKey === imageKey);
    const bestExisting = duplicates
      .map(item => ({ ...item, quality: getKidsNoteImageQuality(item.photo.sourceUrl, item.photo.size) }))
      .sort((a, b) => b.quality - a.quality)[0];
    if (bestExisting && bestExisting.quality >= getKidsNoteImageQuality(sourceUrl, buffer.length)) {
      return null;
    }
    for (const duplicate of duplicates) {
      const filePath = path.join(PHOTO_FILES_DIR, duplicate.photo.filename);
      if (fs.existsSync(filePath)) fs.rmSync(filePath, { force: true });
    }
    const duplicateIndexes = new Set(duplicates.map(item => item.index));
    for (let index = photos.length - 1; index >= 0; index--) {
      if (duplicateIndexes.has(index)) photos.splice(index, 1);
    }
  }

  const id = getPhotoSourceId(sourceUrl);
  const ext = getImageExtension(mimeType, sourceUrl);
  const filename = `${id}${ext}`;
  const filePath = path.join(PHOTO_FILES_DIR, filename);
  if (!fs.existsSync(filePath)) fs.writeFileSync(filePath, buffer);

  const urlPath = new URL(sourceUrl).pathname;
  const originalName = sanitizePhotoName(path.basename(urlPath) || `${sourceType || 'kidsnote'}-${id.slice(0, 8)}${ext}`);
  const photo = {
    id,
    originalName,
    filename,
    mimeType: mimeType || 'image/jpeg',
    size: buffer.length,
    uploadedAt: new Date().toISOString(),
    takenAt: sourceDate || '',
    sourceTitle: sourceTitle || '',
    source: 'kidsnote',
    sourceType,
    sourcePage,
    sourceUrl,
    imageKey
  };
  writePhotoIndex([...photos, photo]);
  return photo;
}

// API Routes
app.get('/photo', (req, res) => {
  res.sendFile(path.join(__dirname, 'public', 'photo.html'));
});

app.get('/api/photos', (req, res) => {
  const sort = String(req.query.sort || 'sourceDateDesc');
  const offset = Math.max(0, Number(req.query.offset) || 0);
  const limit = Math.max(1, Math.min(200, Number(req.query.limit) || 80));
  const query = String(req.query.q || '').trim().toLowerCase();
  const year = String(req.query.year || '').trim();
  const allPhotos = readPhotoIndex()
    .filter(photo => fs.existsSync(path.join(PHOTO_FILES_DIR, photo.filename)))
    .sort((a, b) => {
      const aTaken = String(a.takenAt || '');
      const bTaken = String(b.takenAt || '');
      if (sort === 'sourceDateAsc') {
        if (aTaken && !bTaken) return -1;
        if (!aTaken && bTaken) return 1;
        return aTaken.localeCompare(bTaken) || String(a.uploadedAt).localeCompare(String(b.uploadedAt));
      }
      if (sort === 'uploadedDesc') return String(b.uploadedAt).localeCompare(String(a.uploadedAt));
      if (sort === 'uploadedAsc') return String(a.uploadedAt).localeCompare(String(b.uploadedAt));
      if (aTaken && !bTaken) return -1;
      if (!aTaken && bTaken) return 1;
      return bTaken.localeCompare(aTaken) || String(b.uploadedAt).localeCompare(String(a.uploadedAt));
    });
  const yearCounts = allPhotos.reduce((counts, photo) => {
    const photoYear = String(photo.takenAt || '').slice(0, 4);
    if (/^\d{4}$/.test(photoYear)) counts[photoYear] = (counts[photoYear] || 0) + 1;
    return counts;
  }, {});
  const filteredPhotos = allPhotos
    .filter(photo => !/^\d{4}$/.test(year) || String(photo.takenAt || '').startsWith(year))
    .filter(photo => {
      if (!query) return true;
      const haystack = `${photo.originalName || ''} ${photo.sourceType || ''} ${photo.sourceTitle || ''} ${photo.sourceUrl || ''}`.toLowerCase();
      return haystack.includes(query);
    });
  const totalSize = allPhotos.reduce((sum, photo) => sum + (Number(photo.size) || 0), 0);
  res.json({
    photos: filteredPhotos.slice(offset, offset + limit),
    totalCount: filteredPhotos.length,
    allCount: allPhotos.length,
    totalSize,
    yearCounts,
    selectedYear: /^\d{4}$/.test(year) ? year : '',
    offset,
    limit,
    hasMore: offset + limit < filteredPhotos.length
  });
});

app.get('/api/photos/:id/thumb', async (req, res) => {
  const { photo } = getPhotoById(req.params.id);
  if (!photo) return res.status(404).json({ error: '사진을 찾을 수 없습니다.' });
  const thumbPath = await ensurePhotoThumbnail(photo);
  if (thumbPath) {
    res.setHeader('Cache-Control', 'public, max-age=31536000, immutable');
    return res.type('jpg').sendFile(thumbPath);
  }
  const filePath = getPhotoFilePath(photo);
  if (!filePath || !fs.existsSync(filePath)) return res.status(404).json({ error: '사진 파일을 찾을 수 없습니다.' });
  res.setHeader('Cache-Control', 'public, max-age=86400');
  return res.sendFile(filePath);
});

app.get('/api/photos/download-all', (req, res) => {
  const photos = readPhotoIndex().filter(photo => fs.existsSync(path.join(PHOTO_FILES_DIR, photo.filename)));
  if (!photos.length) return res.status(404).json({ error: '다운로드할 사진이 없습니다.' });

  const date = new Date().toISOString().slice(0, 10);
  const archive = archiver('zip', { zlib: { level: 9 } });
  const usedNames = new Map();

  res.statusCode = 200;
  res.setHeader('Content-Type', 'application/zip');
  res.setHeader('Content-Disposition', `attachment; filename="kidsnote-photos-${date}.zip"`);
  archive.on('error', error => {
    console.error('Failed to create photo archive:', error.message);
    if (!res.headersSent) res.status(500).json({ error: '사진 압축 파일을 만들지 못했습니다.' });
    else res.destroy(error);
  });
  archive.pipe(res);

  for (const photo of photos) {
    const filePath = path.join(PHOTO_FILES_DIR, photo.filename);
    const baseName = sanitizePhotoName(photo.originalName || photo.filename);
    const extension = path.extname(baseName);
    const stem = extension ? baseName.slice(0, -extension.length) : baseName;
    const count = usedNames.get(baseName) || 0;
    const archiveName = count === 0 ? baseName : `${stem} (${count + 1})${extension}`;
    usedNames.set(baseName, count + 1);
    archive.file(filePath, { name: archiveName });
  }

  archive.finalize();
});

app.get('/api/photos/:id/file', (req, res) => {
  const { photo } = getPhotoById(req.params.id);
  if (!photo) return res.status(404).json({ error: '사진을 찾을 수 없습니다.' });
  const filePath = getPhotoFilePath(photo);
  if (!fs.existsSync(filePath)) return res.status(404).json({ error: '사진 파일을 찾을 수 없습니다.' });
  res.type(photo.mimeType || 'application/octet-stream');
  if (req.query.download === '1') {
    res.download(filePath, photo.originalName || photo.filename);
  } else {
    res.sendFile(filePath);
  }
});

app.delete('/api/photos/:id', (req, res) => {
  const { photos, photo } = getPhotoById(req.params.id);
  if (!photo) return res.status(404).json({ error: '사진을 찾을 수 없습니다.' });
  const filePath = getPhotoFilePath(photo);
  const thumbPath = getPhotoThumbPath(photo);
  if (fs.existsSync(filePath)) fs.unlinkSync(filePath);
  if (thumbPath && fs.existsSync(thumbPath)) fs.unlinkSync(thumbPath);
  writePhotoIndex(photos.filter(item => item.id !== req.params.id));
  res.json({ deleted: true });
});



function stripHtml(value) {
  return String(value || '').replace(/<[^>]+>/g, ' ').replace(/&nbsp;/gi, ' ').replace(/\s+/g, ' ').trim();
}

function getKidsNoteReports(payload) {
  if (Array.isArray(payload)) return payload;
  if (!payload || typeof payload !== 'object') return [];
  for (const key of ['results', 'reports', 'items', 'data']) {
    const value = payload[key];
    if (Array.isArray(value)) return value;
    if (value && typeof value === 'object') {
      const nested = getKidsNoteReports(value);
      if (nested.length) return nested;
    }
  }
  return [];
}

function getKidsNoteNextCollectionUrl(nextValue, endpoint) {
  if (!nextValue) return '';
  const text = String(nextValue).trim();
  if (!text) return '';
  if (/^(https?:)?\/\//i.test(text) || text.startsWith('/')) {
    try {
      const url = new URL(text, endpoint);
      if (/^https?:$/.test(url.protocol) && url.pathname.includes('/api/')) return url.href;
    } catch {}
  }
  const url = new URL(endpoint);
  if (url.searchParams.has('cursor')) url.searchParams.set('cursor', text);
  else if (url.searchParams.has('page')) url.searchParams.set('page', text);
  else url.searchParams.set('cursor', text);
  return url.href;
}

function parseRequestCookies(req) {
  return String(req.headers.cookie || '').split(';').reduce((cookies, part) => {
    const separator = part.indexOf('=');
    if (separator < 1) return cookies;
    cookies[part.slice(0, separator).trim()] = decodeURIComponent(part.slice(separator + 1).trim());
    return cookies;
  }, {});
}

function mergeSetCookies(existingCookie, setCookieHeaders = []) {
  const values = new Map();
  String(existingCookie || '').split(';').forEach(part => {
    const separator = part.indexOf('=');
    if (separator > 0) values.set(part.slice(0, separator).trim(), part.slice(separator + 1).trim());
  });
  for (const header of setCookieHeaders || []) {
    const pair = String(header).split(';', 1)[0];
    const separator = pair.indexOf('=');
    if (separator > 0) values.set(pair.slice(0, separator).trim(), pair.slice(separator + 1).trim());
  }
  return Array.from(values, ([name, value]) => `${name}=${value}`).join('; ');
}

function kidsNoteWebRequest({ method = 'GET', requestPath, body = '', cookie = '' }) {
  return new Promise((resolve, reject) => {
    const request = https.request({
      hostname: 'www.kidsnote.com',
      port: 443,
      path: requestPath,
      method,
      headers: {
        Accept: 'text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8',
        'Accept-Language': 'ko-KR,ko;q=0.9',
        'Content-Type': 'application/x-www-form-urlencoded',
        'Content-Length': Buffer.byteLength(body),
        Origin: 'https://www.kidsnote.com',
        Referer: 'https://www.kidsnote.com/login',
        Cookie: cookie,
        'User-Agent': 'Mozilla/5.0 NEO-Planner-KidsNote-Connector/1.0'
      },
      timeout: 15000
    }, response => {
      const chunks = [];
      let size = 0;
      response.on('data', chunk => {
        size += chunk.length;
        if (size <= 2 * 1024 * 1024) chunks.push(chunk);
      });
      response.on('end', () => resolve({
        status: response.statusCode || 0,
        location: response.headers.location || '',
        setCookies: response.headers['set-cookie'] || [],
        body: Buffer.concat(chunks).toString('utf8')
      }));
    });
    request.on('timeout', () => request.destroy(new Error('키즈노트 로그인 시간이 초과되었습니다.')));
    request.on('error', reject);
    if (body) request.write(body);
    request.end();
  });
}

async function loginToKidsNote(username, password) {
  const loginPage = await kidsNoteWebRequest({ requestPath: '/login' });
  let cookie = mergeSetCookies('', loginPage.setCookies);
  const body = new URLSearchParams({ username, password, remember_me: 'on' }).toString();
  const result = await kidsNoteWebRequest({ method: 'POST', requestPath: '/kr/login', body, cookie });
  cookie = mergeSetCookies(cookie, result.setCookies);
  const redirectedAwayFromLogin = result.status >= 300 && result.status < 400 && result.location && !/\/login(?:\?|$)/.test(result.location);
  if (!redirectedAwayFromLogin || !cookie) {
    const error = new Error('키즈노트 아이디 또는 비밀번호가 올바르지 않거나 추가 인증이 필요합니다.');
    error.status = 401;
    throw error;
  }
  return cookie;
}

async function loginToKidsNoteBrowser(username, password) {
  const puppeteer = require('puppeteer-core');
  let browser;
  try {
    browser = await puppeteer.launch({
      executablePath: CHROMIUM_EXECUTABLE,
      headless: true,
      args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-dev-shm-usage'],
      timeout: 30000
    });
    const page = await browser.newPage();
    let childId = '';
    let enrollment = '';
    page.on('request', request => {
      const match = request.url().match(/\/children\/(\d+)\/reports(?:\/|\?)/);
      if (!match) return;
      childId = childId || match[1];
      const headers = request.headers();
      enrollment = enrollment || headers['x-enrollment'] || '';
    });

    await page.goto('https://www.kidsnote.com/login', { waitUntil: 'domcontentloaded', timeout: 30000 });
    await page.waitForSelector('input[name="username"]', { timeout: 15000 });
    await page.type('input[name="username"]', username);
    await page.type('input[name="password"]', password);
    const loginOutcome = Promise.race([
      page.waitForNavigation({ waitUntil: 'domcontentloaded', timeout: 20000 }).catch(() => null),
      page.waitForFunction(() => {
        const loginPath = /^\/(?:[a-z]{2}\/)?login\/?$/.test(location.pathname);
        const invalidInput = Boolean(document.querySelector('input[aria-invalid="true"]'));
        return !loginPath || invalidInput;
      }, { timeout: 20000 }).catch(() => null)
    ]);
    await page.click('button[type="submit"]');
    await loginOutcome;

    if (/\/(?:[a-z]{2}\/)?login(?:\/|\?|$)/.test(page.url())) {
      const error = new Error('키즈노트 아이디 또는 비밀번호가 올바르지 않거나 추가 인증이 필요합니다.');
      error.status = 401;
      throw error;
    }

    await page.goto('https://www.kidsnote.com/service/report', { waitUntil: 'domcontentloaded', timeout: 30000 });
    if (!childId) {
      try {
        await page.waitForFunction(() => performance.getEntriesByType('resource').some(entry => /\/children\/\d+\/reports/.test(entry.name)), { timeout: 20000 });
      } catch {
        await new Promise(resolve => setTimeout(resolve, 1500));
      }
    }

    const cookies = await page.cookies();
    const cookie = cookies.map(item => `${item.name}=${item.value}`).join('; ');
    if (!cookie || !childId) {
      const error = new Error('로그인은 되었지만 자녀 알림장 정보를 찾지 못했습니다. 키즈노트에서 자녀 연결 상태를 확인해 주세요.');
      error.status = 422;
      throw error;
    }
    return { cookie, childId, enrollment };
  } finally {
    if (browser) await browser.close().catch(() => {});
  }
}

function getKidsNoteEncryptionKey() {
  if (!KIDSNOTE_SESSION_SECRET || KIDSNOTE_SESSION_SECRET.length < 32) {
    const error = new Error('키즈노트 세션 암호화 키가 설정되지 않았습니다.');
    error.status = 503;
    throw error;
  }
  return crypto.createHash('sha256').update(KIDSNOTE_SESSION_SECRET).digest();
}

function encryptKidsNoteCookie(value) {
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv('aes-256-gcm', getKidsNoteEncryptionKey(), iv);
  const ciphertext = Buffer.concat([cipher.update(value, 'utf8'), cipher.final()]);
  return `${iv.toString('base64url')}.${cipher.getAuthTag().toString('base64url')}.${ciphertext.toString('base64url')}`;
}

function decryptKidsNoteCookie(value) {
  const [ivValue, tagValue, ciphertextValue] = String(value || '').split('.');
  if (!ivValue || !tagValue || !ciphertextValue) throw new Error('저장된 키즈노트 세션이 손상되었습니다.');
  const decipher = crypto.createDecipheriv('aes-256-gcm', getKidsNoteEncryptionKey(), Buffer.from(ivValue, 'base64url'));
  decipher.setAuthTag(Buffer.from(tagValue, 'base64url'));
  return Buffer.concat([decipher.update(Buffer.from(ciphertextValue, 'base64url')), decipher.final()]).toString('utf8');
}

function readKidsNoteSessions() {
  try {
    const sessions = JSON.parse(fs.readFileSync(KIDSNOTE_SESSION_FILE, 'utf8'));
    return sessions && typeof sessions === 'object' ? sessions : {};
  } catch (error) {
    if (error.code !== 'ENOENT') console.error('Failed to read KidsNote sessions:', error.message);
    return {};
  }
}

function writeKidsNoteSessions(sessions) {
  fs.mkdirSync(path.dirname(KIDSNOTE_SESSION_FILE), { recursive: true });
  const temporaryPath = `${KIDSNOTE_SESSION_FILE}.tmp`;
  fs.writeFileSync(temporaryPath, JSON.stringify(sessions, null, 2), { encoding: 'utf8', mode: 0o600 });
  fs.renameSync(temporaryPath, KIDSNOTE_SESSION_FILE);
}

function saveKidsNoteSession(childId, cookie, enrollment = '') {
  const sessions = readKidsNoteSessions();
  const now = Date.now();
  for (const [key, session] of Object.entries(sessions)) {
    if (!session?.expiresAt || session.expiresAt <= now) delete sessions[key];
  }
  const token = crypto.randomBytes(32).toString('base64url');
  sessions[token] = {
    childId: String(childId),
    encryptedCookie: encryptKidsNoteCookie(JSON.stringify({ cookie, enrollment })),
    createdAt: now,
    expiresAt: now + KIDSNOTE_SESSION_TTL_MS
  };
  writeKidsNoteSessions(sessions);
  return token;
}

function getSavedKidsNoteSession(req) {
  const token = parseRequestCookies(req)[KIDSNOTE_SESSION_COOKIE];
  if (!token) return null;
  const sessions = readKidsNoteSessions();
  const session = sessions[token];
  if (!session || session.expiresAt <= Date.now()) {
    if (session) {
      delete sessions[token];
      writeKidsNoteSessions(sessions);
    }
    return null;
  }
  try {
    const decrypted = decryptKidsNoteCookie(session.encryptedCookie);
    let credentials;
    try {
      credentials = JSON.parse(decrypted);
    } catch {
      credentials = { cookie: decrypted, enrollment: '' };
    }
    return { token, childId: session.childId, cookie: credentials.cookie, enrollment: credentials.enrollment || '', expiresAt: session.expiresAt };
  } catch (error) {
    console.error('Failed to decrypt KidsNote session:', error.message);
    return null;
  }
}

function clearSavedKidsNoteSession(req, res) {
  const token = parseRequestCookies(req)[KIDSNOTE_SESSION_COOKIE];
  if (token) {
    const sessions = readKidsNoteSessions();
    delete sessions[token];
    writeKidsNoteSessions(sessions);
  }
  res.setHeader('Set-Cookie', `${KIDSNOTE_SESSION_COOKIE}=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0`);
}

function parseCookieHeader(cookieHeader) {
  return String(cookieHeader || '').split(';').map(part => {
    const separator = part.indexOf('=');
    if (separator < 1) return null;
    return {
      name: part.slice(0, separator).trim(),
      value: part.slice(separator + 1).trim(),
      domain: '.kidsnote.com',
      path: '/'
    };
  }).filter(cookie => cookie?.name && cookie.value);
}

function isLikelyKidsNoteImageUrl(value) {
  if (!value || /^(data:|blob:|javascript:)/i.test(value)) return false;
  let url;
  try {
    url = new URL(value, 'https://www.kidsnote.com');
  } catch {
    return false;
  }
  if (!/^https?:$/.test(url.protocol)) return false;
  const host = url.hostname.toLowerCase();
  if (/(facebook|google|doubleclick|analytics|googletagmanager|sentry|intercom)/.test(host)) return false;
  const pathname = url.pathname.toLowerCase();
  if (/\.(svg|ico)$/i.test(pathname)) return false;
  return /\.(jpe?g|png|webp|gif|heic)(?:$|\?)/i.test(`${pathname}${url.search}`) ||
    /(kidsnote|amazonaws|cloudfront|cdn|image|photo|album|report)/i.test(`${host}${pathname}`);
}

function collectImageUrlsDeep(value, urls = new Set()) {
  if (typeof value === 'string') {
    if (isLikelyKidsNoteImageUrl(value)) urls.add(new URL(value, 'https://www.kidsnote.com').href);
    return urls;
  }
  if (Array.isArray(value)) {
    value.forEach(item => collectImageUrlsDeep(item, urls));
    return urls;
  }
  if (value && typeof value === 'object') {
    Object.values(value).forEach(item => collectImageUrlsDeep(item, urls));
  }
  return urls;
}

function getKidsNoteItemId(item) {
  const value = item?.id || item?.uuid || item?.report_id || item?.album_id || item?.pk;
  return value == null ? '' : String(value).trim();
}

function getKidsNoteItemDate(item) {
  const value = item?.date_written || item?.written_at || item?.created_at || item?.created || item?.date || item?.updated_at || '';
  const match = String(value || '').match(/^(\d{4}-\d{2}-\d{2})(?:[T\s](\d{2}:\d{2}(?::\d{2})?))?/);
  if (!match) return '';
  return match[2] ? `${match[1]}T${match[2]}` : match[1];
}

function getKidsNoteItemTitle(item) {
  return stripHtml(item?.title || item?.subject || item?.name || item?.content_title || '').slice(0, 120);
}

function normalizeKidsNoteServiceUrl(value) {
  if (!value) return '';
  let url;
  try {
    url = new URL(String(value), 'https://www.kidsnote.com');
  } catch {
    return '';
  }
  if (url.hostname !== 'www.kidsnote.com') return '';
  if (!/^\/service\/(report|album)(?:\/\d+)?\/?$/.test(url.pathname)) return '';
  return url.href;
}

async function collectKidsNoteImageUrls(page, sourceType, sourcePage, candidates, discoveredPages) {
  const result = await page.evaluate(() => {
    const urls = new Set();
    const links = new Set();
    const addUrl = value => {
      if (!value || /^(data:|blob:|javascript:)/i.test(value)) return;
      try {
        urls.add(new URL(value, location.href).href);
      } catch {}
    };
    document.querySelectorAll('img').forEach(img => {
      addUrl(img.currentSrc || img.src);
      addUrl(img.getAttribute('data-src'));
      addUrl(img.getAttribute('data-original'));
      addUrl(img.getAttribute('data-lazy'));
      addUrl(img.getAttribute('srcset')?.split(',').pop()?.trim()?.split(/\s+/)[0]);
    });
    document.querySelectorAll('[style*=\"background\"]').forEach(element => {
      const style = element.getAttribute('style') || '';
      for (const match of style.matchAll(/url\(["']?([^"')]+)["']?\)/g)) addUrl(match[1]);
    });
    document.querySelectorAll('a[href]').forEach(anchor => {
      try {
        const href = new URL(anchor.getAttribute('href'), location.href).href;
        if (/\/service\/(report|album)/.test(new URL(href).pathname)) links.add(href);
      } catch {}
    });
    return { urls: Array.from(urls), links: Array.from(links) };
  });

  for (const url of result.urls) {
    if (isLikelyKidsNoteImageUrl(url)) candidates.set(url, { sourceType, sourcePage });
  }
  for (const link of result.links) {
    if (!discoveredPages.has(link)) discoveredPages.add(link);
  }
}

async function settleKidsNotePage(page) {
  for (let round = 0; round < 3; round++) {
    await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
    await new Promise(resolve => setTimeout(resolve, 500));
    const clicked = await page.evaluate(() => {
      const buttons = Array.from(document.querySelectorAll('button, a')).filter(element => {
        const text = (element.textContent || '').trim();
        return /더\\s*보기|more|다음/i.test(text) && !element.disabled;
      });
      const target = buttons[0];
      if (!target) return false;
      target.click();
      return true;
    }).catch(() => false);
    if (!clicked) {
      const before = await page.evaluate(() => document.body.scrollHeight).catch(() => 0);
      await new Promise(resolve => setTimeout(resolve, 300));
      const after = await page.evaluate(() => document.body.scrollHeight).catch(() => 0);
      if (after <= before) break;
    }
  }
}

async function withTimeout(promise, timeoutMs, label) {
  let timer;
  try {
    return await Promise.race([
      promise,
      new Promise((_, reject) => {
        timer = setTimeout(() => reject(new Error(`${label} 시간이 초과되었습니다.`)), timeoutMs);
      })
    ]);
  } finally {
    clearTimeout(timer);
  }
}

async function downloadKidsNoteImage(sourceUrl, session, meta) {
  const response = await fetch(sourceUrl, {
    headers: {
      Cookie: session.cookie,
      Accept: 'image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8',
      Referer: meta.sourcePage || 'https://www.kidsnote.com/service/report',
      ...(session.enrollment ? { 'X-ENROLLMENT': session.enrollment } : {}),
      'User-Agent': 'Mozilla/5.0 NEO-Planner-KidsNote-PhotoBackup/1.0'
    },
    redirect: 'follow',
    signal: AbortSignal.timeout(30000)
  });
  if (!response.ok) return null;
  const mimeType = response.headers.get('content-type') || '';
  if (!mimeType.toLowerCase().startsWith('image/')) return null;
  const buffer = Buffer.from(await response.arrayBuffer());
  return addBackedUpPhoto({
    sourceUrl,
    buffer,
    mimeType,
    sourcePage: meta.sourcePage,
    sourceType: meta.sourceType,
    sourceDate: meta.sourceDate,
    sourceTitle: meta.sourceTitle
  });
}

function getKidsNoteImageUrlsFromItem(item) {
  const urls = new Set();
  const images = [
    ...(Array.isArray(item?.attached_images) ? item.attached_images : []),
    ...(Array.isArray(item?.images) ? item.images : []),
    ...(Array.isArray(item?.photos) ? item.photos : [])
  ];
  for (const image of images) {
    if (typeof image === 'string') {
      if (isLikelyKidsNoteImageUrl(image)) urls.add(new URL(image).href);
      continue;
    }
    for (const key of ['original', 'download_url', 'file', 'image', 'url', 'large']) {
      if (image?.[key] && isLikelyKidsNoteImageUrl(image[key])) {
        urls.add(new URL(image[key]).href);
        break;
      }
    }
  }
  return Array.from(urls);
}

async function fetchKidsNoteCollection(childId, cookie, collection, options = {}) {
  const items = [];
  const endpoint = `https://www.kidsnote.com/api/v1_2/children/${childId}/${collection}/?page_size=5000`;
  let nextUrl = endpoint;
  const maxPages = Math.max(1, Math.min(50, Number(options.maxPages) || 50));
  const seenUrls = new Set();
  for (let page = 0; nextUrl && page < maxPages; page++) {
    const url = new URL(nextUrl, endpoint);
    if (seenUrls.has(url.href)) break;
    seenUrls.add(url.href);
    const expectedPath = new RegExp(`/children/${String(childId)}/${collection}(?:/|$)`);
    if (url.protocol !== 'https:' || !['www.kidsnote.com', 'kapi.kidsnote.com'].includes(url.hostname) || !expectedPath.test(url.pathname)) {
      throw new Error(`키즈노트 ${collection} 다음 페이지 주소가 올바르지 않습니다.`);
    }
    const response = await fetch(url, {
      headers: {
        Cookie: cookie.trim(),
        Accept: 'application/json',
        ...(options.enrollment ? { 'X-ENROLLMENT': options.enrollment } : {}),
        'User-Agent': 'NEO-Planner-KidsNote-PhotoBackup/1.0'
      },
      redirect: 'manual',
      signal: AbortSignal.timeout(20000)
    });
    if (response.status === 401 || response.status === 403 || response.status === 302) {
      const error = new Error('키즈노트 로그인이 만료되었습니다. 다시 로그인해 주세요.');
      error.status = 401;
      throw error;
    }
    if (!response.ok) throw new Error(`키즈노트 ${collection} 조회에 실패했습니다. (${response.status})`);
    const payload = await response.json();
    items.push(...getKidsNoteReports(payload));
    const resolvedNextUrl = getKidsNoteNextCollectionUrl(payload.next, endpoint);
    nextUrl = resolvedNextUrl && !seenUrls.has(resolvedNextUrl) ? resolvedNextUrl : '';
  }
  return items;
}

async function crawlKidsNotePhotos(session, job, options = {}) {
  const candidates = new Map();
  const collections = [
    { name: 'reports', sourceType: 'report', servicePath: 'report' },
    { name: 'albums', sourceType: 'album', servicePath: 'album' }
  ];

  for (const collection of collections) {
    let items;
    try {
      job.progress.currentPage = `api:${collection.name}`;
      items = await fetchKidsNoteCollection(session.childId, session.cookie, collection.name, {
        enrollment: session.enrollment,
        maxPages: options.maxPages
      });
    } catch (error) {
      job.progress.failedPages = (job.progress.failedPages || 0) + 1;
      console.warn(`KidsNote ${collection.name} API scan failed:`, error.message);
      continue;
    }

    job.progress.pagesVisited = (job.progress.pagesVisited || 0) + 1;
    for (const item of items) {
      const itemId = getKidsNoteItemId(item);
      const sourceDate = getKidsNoteItemDate(item);
      const sourceTitle = getKidsNoteItemTitle(item);
      const sourcePage = itemId && /^\d+$/.test(itemId)
        ? `https://www.kidsnote.com/service/${collection.servicePath}/${itemId}`
        : `https://www.kidsnote.com/service/${collection.servicePath}`;
      for (const imageUrl of getKidsNoteImageUrlsFromItem(item)) {
        candidates.set(imageUrl, { sourceType: collection.sourceType, sourcePage, sourceDate, sourceTitle });
      }
    }
    job.progress.found = candidates.size;
    job.progress[collection.name] = items.length;
  }

  const existingPhotosByUrl = buildExistingPhotoUrlMap();
  const existingUrls = new Set(existingPhotosByUrl.keys());
  let saved = 0;
  let skipped = 0;
  let failed = 0;
  let processed = 0;
  const entries = Array.from(candidates.entries());
  for (const [sourceUrl, meta] of entries) {
    processed++;
    job.progress = { ...job.progress, found: entries.length, processed, saved, skipped, failed, currentImage: sourceUrl };
    if (existingUrls.has(sourceUrl)) {
      if (shouldUpdatePhotoMeta(existingPhotosByUrl.get(sourceUrl), meta)) {
        updateExistingPhotoMeta(sourceUrl, meta);
      }
      skipped++;
      continue;
    }
    try {
      const photo = await downloadKidsNoteImage(sourceUrl, session, meta);
      if (photo) {
        existingUrls.add(sourceUrl);
        saved++;
      } else {
        skipped++;
      }
    } catch (error) {
      failed++;
      console.warn('KidsNote photo download failed:', sourceUrl, error.message);
    }
  }
  return { found: entries.length, saved, skipped, failed, pagesVisited: job.progress.pagesVisited || 0 };
}




app.get('/', (req, res) => res.redirect('/photo'));

app.get('/api/photo-kidsnote/session', (req, res) => {
  const session = getSavedKidsNoteSession(req);
  res.setHeader('Cache-Control', 'no-store');
  res.json(session
    ? { connected: true, childId: session.childId, expiresAt: new Date(session.expiresAt).toISOString() }
    : { connected: false });
});

app.post('/api/photo-kidsnote/login', async (req, res) => {
  const { username, password } = req.body || {};
  if (!username || typeof username !== 'string' || username.length > 100 ||
      !password || typeof password !== 'string' || password.length > 200) {
    return res.status(400).json({ error: '키즈노트 아이디와 비밀번호를 확인해 주세요.' });
  }
  try {
    const login = await loginToKidsNoteBrowser(username.trim(), password);
    await fetchKidsNoteCollection(login.childId, login.cookie, 'reports', { maxPages: 1, enrollment: login.enrollment });
    const token = saveKidsNoteSession(login.childId, login.cookie, login.enrollment);
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('Set-Cookie', `${KIDSNOTE_SESSION_COOKIE}=${encodeURIComponent(token)}; HttpOnly; SameSite=Strict; Path=/; Max-Age=${Math.floor(KIDSNOTE_SESSION_TTL_MS / 1000)}`);
    res.json({ connected: true, childId: login.childId, expiresAt: new Date(Date.now() + KIDSNOTE_SESSION_TTL_MS).toISOString() });
  } catch (error) {
    console.error('KidsNote login error:', error.message);
    res.status(error.status || 502).json({ error: error.message || '키즈노트 로그인에 실패했습니다.' });
  }
});

app.delete('/api/photo-kidsnote/session', (req, res) => {
  clearSavedKidsNoteSession(req, res);
  res.json({ connected: false });
});

app.post('/api/photos/kidsnote-backup/start', (req, res) => {
  const session = getSavedKidsNoteSession(req);
  if (!session) return res.status(401).json({ error: '저장된 키즈노트 로그인이 없거나 만료되었습니다.' });
  for (const [existingJobId, existingJob] of photoBackupJobs.entries()) {
    if (existingJob.ownerToken === session.token && existingJob.status === 'processing') {
      return res.status(202).json({ jobId: existingJobId, status: existingJob.status, reused: true });
    }
  }
  const jobId = crypto.randomBytes(24).toString('base64url');
  const job = {
    ownerToken: session.token,
    status: 'processing',
    createdAt: Date.now(),
    progress: { pagesVisited: 0, found: 0, processed: 0, saved: 0, skipped: 0, failed: 0, currentPage: '', currentImage: '' },
    result: null,
    error: ''
  };
  photoBackupJobs.set(jobId, job);
  setImmediate(async () => {
    try {
      job.result = await crawlKidsNotePhotos(session, job);
      job.progress = { ...job.progress, ...job.result, currentPage: '', currentImage: '' };
      job.status = 'completed';
    } catch (error) {
      console.error('KidsNote photo backup error:', error.message);
      job.error = error.message || '키즈노트 사진 백업에 실패했습니다.';
      job.status = 'failed';
    }
  });
  res.status(202).json({ jobId, status: job.status });
});

app.get('/api/photos/kidsnote-backup/jobs/:jobId', (req, res) => {
  const session = getSavedKidsNoteSession(req);
  const job = photoBackupJobs.get(req.params.jobId);
  if (!session || !job || job.ownerToken !== session.token) {
    return res.status(404).json({ error: '사진 백업 작업을 찾을 수 없습니다.' });
  }
  if (job.status === 'completed') {
    photoBackupJobs.delete(req.params.jobId);
    return res.json({ status: 'completed', result: job.result, progress: job.progress });
  }
  if (job.status === 'failed') {
    photoBackupJobs.delete(req.params.jobId);
    return res.status(500).json({ status: 'failed', error: job.error, progress: job.progress });
  }
  res.json({ status: 'processing', progress: job.progress });
});

setInterval(() => {
  const cutoff = Date.now() - 2 * 60 * 60 * 1000;
  for (const [jobId, job] of photoBackupJobs) {
    if (job.createdAt < cutoff) photoBackupJobs.delete(jobId);
  }
}, 10 * 60 * 1000).unref();

ensurePhotoBackupStore();
app.listen(PORT, '0.0.0.0', () => {
  console.log(`KidsNote photo backup is running on http://localhost:${PORT}/photo`);
});
