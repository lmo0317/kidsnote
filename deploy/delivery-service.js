const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const archiver = require('archiver');
const encryptedZip = require('archiver-zip-encrypted');
const nodemailer = require('nodemailer');

archiver.registerFormat('zip-encrypted', encryptedZip);

const RETENTION_MS = 7 * 24 * 60 * 60 * 1000;

function startDeliveryService(app, options) {
  const jobsDir = path.join(options.dataDir, 'delivery-jobs');
  const indexFile = path.join(jobsDir, 'jobs.json');
  const requestsFile = path.join(jobsDir, 'requests.json');
  const jobs = new Map();
  const requests = new Map();
  const requestRates = new Map();
  fs.mkdirSync(jobsDir, { recursive: true });

  function readStoredJobs() {
    try {
      const stored = JSON.parse(fs.readFileSync(indexFile, 'utf8'));
      for (const job of Array.isArray(stored) ? stored : []) jobs.set(job.id, job);
    } catch (error) {
      if (error.code !== 'ENOENT') console.error('Delivery job history read failed:', error.message);
    }
  }

  function saveJobs() {
    const visible = [...jobs.values()].map(({ password, ...job }) => job);
    const temporary = `${indexFile}.tmp`;
    fs.writeFileSync(temporary, JSON.stringify(visible, null, 2), { mode: 0o600 });
    fs.renameSync(temporary, indexFile);
  }

  function requestCipherKey() {
    const secret = String(process.env.KIDSNOTE_SESSION_SECRET || '');
    if (secret.length < 32) throw new Error('신청 정보 암호화 키가 설정되지 않았습니다.');
    return crypto.createHash('sha256').update(secret).digest();
  }

  function encryptCredentials(value) {
    const iv = crypto.randomBytes(12);
    const cipher = crypto.createCipheriv('aes-256-gcm', requestCipherKey(), iv);
    const encrypted = Buffer.concat([cipher.update(JSON.stringify(value)), cipher.final()]);
    return `${iv.toString('base64url')}.${cipher.getAuthTag().toString('base64url')}.${encrypted.toString('base64url')}`;
  }

  function decryptCredentials(value) {
    const [iv, tag, encrypted] = String(value || '').split('.');
    const decipher = crypto.createDecipheriv('aes-256-gcm', requestCipherKey(), Buffer.from(iv, 'base64url'));
    decipher.setAuthTag(Buffer.from(tag, 'base64url'));
    return JSON.parse(Buffer.concat([decipher.update(Buffer.from(encrypted, 'base64url')), decipher.final()]));
  }

  function readRequests() {
    try { for (const item of JSON.parse(fs.readFileSync(requestsFile, 'utf8'))) requests.set(item.id, item); }
    catch (error) { if (error.code !== 'ENOENT') console.error('Delivery requests read failed:', error.message); }
  }

  function saveRequests() {
    const temporary = `${requestsFile}.tmp`;
    fs.writeFileSync(temporary, JSON.stringify([...requests.values()], null, 2), { mode: 0o600 });
    fs.renameSync(temporary, requestsFile);
  }

  function publicRequest(item) {
    return { id: item.id, email: item.email, year: item.year, deliveryNote: item.deliveryNote || '', includeNotices: item.includeNotices === true, folderMode: item.folderMode || 'day', sampleMode: item.sampleMode === true, status: item.status, createdAt: item.createdAt, updatedAt: item.updatedAt, jobId: item.jobId || '', error: item.error || '', canRerun: Boolean(item.encryptedCredentials) };
  }

  function isAdmin(req) {
    const expected = String(process.env.ANALYTICS_ADMIN_PASSWORD || '');
    const supplied = String(req.get('x-analytics-password') || '');
    const a = Buffer.from(expected); const b = Buffer.from(supplied);
    return a.length > 0 && a.length === b.length && crypto.timingSafeEqual(a, b);
  }

  function requireAdmin(req, res, next) {
    if (isAdmin(req)) return next();
    res.status(401).json({ error: '관리자 비밀번호를 확인해 주세요.' });
  }

  function publicJob(job) {
    return {
      id: job.id, email: job.email, year: job.year, status: job.status,
      includeNotices: job.includeNotices === true, folderMode: job.folderMode || 'day', sampleMode: job.sampleMode === true,
      createdAt: job.createdAt, updatedAt: job.updatedAt, expiresAt: job.expiresAt,
      progress: job.progress, result: job.result, error: job.error
    };
  }

  function update(job, patch = {}) {
    Object.assign(job, patch, { updatedAt: Date.now() });
    saveJobs();
  }

  function ensureNotCancelled(job) {
    if (!job.cancelRequested) return;
    const error = new Error('관리자가 작업을 취소했습니다.');
    error.code = 'JOB_CANCELLED';
    throw error;
  }

  function addToBreakdown(progress, sourceDate) {
    const match = String(sourceDate || '').match(/^(\d{4})-(\d{2})/);
    const year = match ? match[1] : '날짜미상';
    const month = match ? match[2] : '미상';
    progress.breakdown = progress.breakdown || {};
    progress.breakdown[year] = progress.breakdown[year] || { total: 0, months: {} };
    progress.breakdown[year].total++;
    progress.breakdown[year].months[month] = (progress.breakdown[year].months[month] || 0) + 1;
  }

  function sanitizeChildFolderName(name, id, index) {
    let clean = String(name || '').replace(/[\\/:*?"<>|\r\n\t]/g, '_').trim();
    if (!clean) clean = id ? `자녀_${id}` : `자녀_${index + 1}`;
    return clean;
  }

  function datedRelativeDir(sourceDate, folderMode = 'day', childFolderName = '') {
    const match = String(sourceDate || '').match(/^(\d{4})-(\d{2})-(\d{2})/);
    const dateDir = !match
      ? '날짜미상'
      : (folderMode === 'day'
          ? path.join(`${match[1]}년`, `${match[2]}월`, `${match[3]}일`)
          : path.join(`${match[1]}년`, `${match[2]}월`));
    return childFolderName ? path.join(childFolderName, dateDir) : dateDir;
  }

  async function downloadImage(sourceUrl, session, meta, outputDir, usedNames, folderMode = 'day') {
    const response = await fetch(sourceUrl, {
      headers: {
        Cookie: session.cookie,
        Accept: 'image/avif,image/webp,image/apng,image/*,*/*;q=0.8',
        Referer: meta.sourcePage || 'https://www.kidsnote.com/service/report',
        ...(meta.enrollment || session.enrollment ? { 'X-ENROLLMENT': meta.enrollment || session.enrollment } : {}),
        'User-Agent': 'KidsNote-Delivery-Service/1.0'
      },
      redirect: 'follow', signal: AbortSignal.timeout(30000)
    });
    if (!response.ok) throw new Error(`이미지 응답 오류 ${response.status}`);
    const mimeType = response.headers.get('content-type') || '';
    if (!mimeType.startsWith('image/')) throw new Error('이미지 형식이 아닙니다.');
    const buffer = Buffer.from(await response.arrayBuffer());
    if (buffer.length < 1024) throw new Error('이미지 파일이 비어 있습니다.');
    let extension = mimeType.includes('png') ? '.png' : mimeType.includes('webp') ? '.webp' : mimeType.includes('gif') ? '.gif' : '.jpg';
    let base = `${String(meta.sourceDate || '날짜없음').slice(0, 10)}_${crypto.createHash('sha1').update(sourceUrl).digest('hex').slice(0, 12)}${extension}`;
    const nameKey = `${meta.childFolderName || ''}/${base}`;
    const count = usedNames.get(nameKey) || 0;
    usedNames.set(nameKey, count + 1);
    if (count) base = `${path.basename(base, extension)}_${count + 1}${extension}`;
    const relativeDir = datedRelativeDir(meta.sourceDate, folderMode, meta.childFolderName);
    const targetDir = path.join(outputDir, relativeDir);
    fs.mkdirSync(targetDir, { recursive: true, mode: 0o700 });
    fs.writeFileSync(path.join(targetDir, base), buffer, { mode: 0o600 });
    return { name: base, size: buffer.length };
  }

  function decodeHtmlEntities(value) {
    const named = { nbsp: ' ', amp: '&', lt: '<', gt: '>', quot: '"', apos: "'" };
    return value.replace(/&(#x[0-9a-f]+|#\d+|nbsp|amp|lt|gt|quot|apos);/gi, (match, entity) => {
      if (entity[0] !== '#') return named[entity.toLowerCase()] || match;
      const number = entity[1].toLowerCase() === 'x' ? parseInt(entity.slice(2), 16) : parseInt(entity.slice(1), 10);
      return Number.isFinite(number) ? String.fromCodePoint(number) : match;
    });
  }

  function cleanNoticeText(value) {
    return decodeHtmlEntities(String(value || '')
      .replace(/<br\s*\/?>/gi, '\n').replace(/<\/(p|div|li|h[1-6])>/gi, '\n')
      .replace(/<li[^>]*>/gi, '- ').replace(/<[^>]+>/g, ''))
      .replace(/\r/g, '').replace(/[ \t]+\n/g, '\n').replace(/\n{3,}/g, '\n\n').trim();
  }

  function appendNoticeValue(value, output, depth = 0) {
    if (value == null || depth > 4) return;
    if (typeof value === 'string') output.push(value);
    else if (Array.isArray(value)) value.forEach(item => appendNoticeValue(item, output, depth + 1));
    else if (typeof value === 'object') Object.values(value).forEach(item => appendNoticeValue(item, output, depth + 1));
  }

  function noticeFromItem(item, sourceDate) {
    const output = [];
    for (const key of ['content', 'contents', 'description', 'body', 'text', 'memo', 'notice', 'message']) appendNoticeValue(item?.[key], output);
    const title = cleanNoticeText(item?.title || item?.subject || item?.name || '');
    const text = cleanNoticeText(output.join('\n')) || title;
    return text ? { date: String(sourceDate || '').slice(0, 10), title, text } : null;
  }

  function writeNoticeFiles(notices, outputDir, folderMode = 'day') {
    const grouped = new Map();
    for (const notice of notices) {
      const date = /^\d{4}-\d{2}-\d{2}$/.test(notice.date) ? notice.date : '날짜미상';
      const key = `${notice.childFolderName || ''}:::${date}`;
      if (!grouped.has(key)) grouped.set(key, { childFolderName: notice.childFolderName || '', date, items: [] });
      grouped.get(key).items.push(notice);
    }
    let count = 0;
    for (const { childFolderName, date, items } of grouped.values()) {
      const targetDir = path.join(outputDir, datedRelativeDir(date, folderMode, childFolderName));
      fs.mkdirSync(targetDir, { recursive: true, mode: 0o700 });
      const fileName = folderMode === 'day' ? '알림장.txt' : `알림장_${date}.txt`;
      const sections = items.map(item => `${item.title && item.title !== item.text ? `${item.title}\n\n` : ''}${item.text}`);
      fs.writeFileSync(path.join(targetDir, fileName), `키즈노트 알림장\n날짜: ${date}\n\n${sections.join('\n\n========================================\n\n')}\n`, { encoding: 'utf8', mode: 0o600 });
      count += items.length;
    }
    return count;
  }

  function sampleOneTenth(values) {
    if (!values.length) return [];
    const target = Math.max(1, Math.ceil(values.length / 10));
    if (target >= values.length) return [...values];
    const sampled = [];
    for (let index = 0; index < target; index++) sampled.push(values[Math.floor(index * values.length / target)]);
    return sampled;
  }

  async function makeZip(job, filesDir) {
    const zipPath = path.join(jobsDir, job.id, `kidsnote-${job.sampleMode ? 'sample-' : ''}${job.year || 'all'}.zip`);
    job.zipPassword = `KN-${crypto.randomBytes(9).toString('base64url').toUpperCase()}`;

    let sevenZipBin = '';
    const possiblePaths = [
      path.join(__dirname, 'node_modules', '7zip-bin', 'linux', 'x64', '7za'),
      path.join(__dirname, 'node_modules', '7zip-bin', 'win', 'x64', '7za.exe'),
      path.join(__dirname, '..', 'node_modules', '7zip-bin', 'linux', 'x64', '7za'),
      '/usr/bin/7z',
      '/usr/bin/7za'
    ];
    for (const p of possiblePaths) {
      if (fs.existsSync(p)) { sevenZipBin = p; break; }
    }

    if (sevenZipBin) {
      if (fs.existsSync(zipPath)) fs.unlinkSync(zipPath);
      const { execFile } = require('child_process');
      await new Promise((resolve, reject) => {
        execFile(sevenZipBin, [
          'a', '-tzip', `-p${job.zipPassword}`, '-mem=AES256', '-mx=0', '-mmt=on', zipPath, '*'
        ], { cwd: filesDir }, (err, stdout, stderr) => {
          if (err) return reject(new Error(`7-Zip 압축 실패: ${stderr || err.message}`));
          resolve();
        });
      });
      return zipPath;
    }

    await new Promise((resolve, reject) => {
      const output = fs.createWriteStream(zipPath, { mode: 0o600 });
      const archive = archiver.create('zip-encrypted', {
        zlib: { level: 0 }, encryptionMethod: 'aes256', password: job.zipPassword
      });
      output.on('close', resolve); output.on('error', reject); archive.on('error', reject);
      archive.pipe(output); archive.directory(filesDir, false); archive.finalize();
    });
    return zipPath;
  }

  async function sendResultEmail(job, downloadUrl, deleteUrl) {
    const user = process.env.SMTP_USER;
    const pass = process.env.SMTP_APP_PASSWORD;
    if (!user || !pass) throw new Error('이메일 발송 설정이 완료되지 않았습니다.');
    const transporter = nodemailer.createTransport({ host: 'smtp.gmail.com', port: 465, secure: true, auth: { user, pass } });
    const noticeCount = Number(job.result?.noticeCount) || 0;
    const noticeText = noticeCount ? `\n알림장: ${noticeCount}개` : '';
    const noticeHtml = noticeCount ? `<br>알림장 <b>${noticeCount}개</b>` : '';
    const sampleText = job.sampleMode ? `\n샘플 기준: 전체 사진 ${job.result.sourceCount || 0}장 중 ${job.result.count}장` : '';
    const sampleHtml = job.sampleMode ? `<div style="margin:14px 0;padding:12px;border-radius:10px;background:#fff7e6;color:#805f18"><b>1/10 샘플 테스트</b><br>전체 사진 ${job.result.sourceCount || 0}장 중 ${job.result.count}장을 추출했습니다.</div>` : '';
    const passwordText = job.zipPassword ? `\n압축 비밀번호: ${job.zipPassword}\n` : '';
    const passwordHtml = job.zipPassword
      ? `<div style="margin:22px 0;padding:15px;border-radius:10px;background:#f3f8f5"><span style="color:#718078;font-size:13px">압축 비밀번호</span><br><b style="font-size:20px;letter-spacing:.04em">${job.zipPassword}</b></div>` : '';
    await transporter.sendMail({
      from: `키즈노트 사진 전달 <${user}>`, to: job.email,
      subject: `[키즈노트${job.sampleMode ? ' 샘플 테스트' : ''}] ${job.year || '전체 기간'} 자료 다운로드가 준비되었습니다`,
      text: `요청하신 키즈노트 자료가 준비되었습니다.\n사진: ${job.result.count}장${noticeText}${sampleText}\n${passwordText}\n다운로드: ${downloadUrl}\n파일 즉시 삭제: ${deleteUrl}\n\n링크는 7일 후 자동 만료됩니다. 삭제 버튼을 누르면 즉시 내려받을 수 없게 됩니다.`,
      html: `<div style="font-family:Arial,sans-serif;line-height:1.7;color:#17211b"><h2>키즈노트 자료가 준비되었습니다</h2><p><b>${job.year || '전체 기간'}</b><br>사진 <b>${job.result.count}장</b>${noticeHtml}</p>${sampleHtml}${passwordHtml}<p><a href="${downloadUrl}" style="display:inline-block;margin:4px;padding:13px 22px;background:#3e8b63;color:white;text-decoration:none;border-radius:9px;font-weight:700">다운로드</a><a href="${deleteUrl}" style="display:inline-block;margin:4px;padding:12px 21px;border:1px solid #c94b4b;color:#b63838;text-decoration:none;border-radius:9px;font-weight:700">파일 삭제</a></p><p style="color:#718078;font-size:13px">링크는 7일 후 자동 만료됩니다. 삭제 버튼은 확인 화면을 거친 뒤 공유 ZIP을 즉시 삭제합니다.<br>AES-256 압축이며 기본 압축 앱에서 열리지 않으면 7-Zip 또는 WinZip을 사용해 주세요.</p></div>`
    });
  }

  function issueShareTokens(job) {
    const downloadToken = crypto.randomBytes(32).toString('base64url');
    const deleteToken = crypto.randomBytes(32).toString('base64url');
    job.downloadTokenHash = crypto.createHash('sha256').update(downloadToken).digest('hex');
    job.deleteTokenHash = crypto.createHash('sha256').update(deleteToken).digest('hex');
    job.expiresAt = Date.now() + RETENTION_MS;
    return { downloadToken, deleteToken };
  }

  function shareUrls(job, tokens) {
    const baseUrl = String(process.env.PUBLIC_BASE_URL || '').replace(/\/$/, '');
    return {
      downloadUrl: `${baseUrl}/kidsnote-files/delivery/open/${job.id}/${tokens.downloadToken}`,
      deleteUrl: `${baseUrl}/kidsnote-files/delivery/delete/${job.id}/${tokens.deleteToken}`
    };
  }

  async function processJob(job, username, password) {
    const jobDir = path.join(jobsDir, job.id);
    const filesDir = path.join(jobDir, 'files');
    fs.mkdirSync(filesDir, { recursive: true, mode: 0o700 });
    try {
      update(job, { status: 'logging-in', progress: { step: '키즈노트 로그인 중', found: 0, downloaded: 0, failed: 0, breakdown: {} } });
      const session = await options.login(username, password);
      password = ''; username = '';
      ensureNotCancelled(job);
      const sampleLabel = job.sampleMode ? ' · 1/10 샘플' : '';
      update(job, { status: 'collecting', progress: { ...job.progress, step: (job.includeNotices ? '사진과 알림장 목록 수집 중' : '사진 목록 수집 중') + sampleLabel } });

      const rawChildren = Array.isArray(session.children) && session.children.length
        ? session.children
        : [{ id: session.childId, name: '', enrollment: session.enrollment }];

      const seenNames = new Map();
      const children = rawChildren.map((c, idx) => {
        const baseName = sanitizeChildFolderName(c.name, c.id, idx);
        const count = seenNames.get(baseName) || 0;
        seenNames.set(baseName, count + 1);
        const folderName = count > 0 ? `${baseName}_${c.id || count + 1}` : baseName;
        return { ...c, folderName };
      });

      const candidates = new Map();
      const noticeCandidates = [];
      for (const child of children) {
        ensureNotCancelled(job);
        const childFolderName = child.folderName;
        for (const collection of [{ name: 'reports', type: 'report' }, { name: 'albums', type: 'album' }]) {
          const items = await options.fetchCollection(child.id, session.cookie, collection.name, {
            enrollment: child.enrollment || session.enrollment,
            maxPages: 1000
          });
          ensureNotCancelled(job);
          for (const item of items) {
            const date = options.getDate(item);
            if (job.year && !date.startsWith(job.year)) continue;
            if (job.includeNotices && collection.name === 'reports') {
              const notice = noticeFromItem(item, date);
              if (notice) {
                notice.childFolderName = childFolderName;
                noticeCandidates.push(notice);
              }
            }
            for (const sourceUrl of options.getImageUrls(item)) {
              candidates.set(sourceUrl, {
                sourceDate: date,
                sourceType: collection.type,
                sourcePage: `https://www.kidsnote.com/service/${collection.type}`,
                childFolderName,
                childId: child.id,
                enrollment: child.enrollment || session.enrollment
              });
            }
          }
          update(job, { progress: { ...job.progress, found: job.sampleMode && candidates.size ? Math.max(1, Math.ceil(candidates.size / 10)) : candidates.size, sourceFound: candidates.size } });
        }
      }
      if (!candidates.size && !noticeCandidates.length) throw new Error('선택한 기간에 저장할 사진이나 알림장이 없습니다.');
      const sourcePhotoCount = candidates.size;
      const sourceNoticeCount = noticeCandidates.length;
      const selectedCandidates = job.sampleMode ? sampleOneTenth([...candidates.entries()]) : [...candidates.entries()];
      const selectedNotices = job.sampleMode ? sampleOneTenth(noticeCandidates) : noticeCandidates;
      update(job, { status: 'downloading', progress: { ...job.progress, step: job.sampleMode ? '1/10 샘플 사진 다운로드 중' : '사진 다운로드 중', found: selectedCandidates.length, sourceFound: sourcePhotoCount } });
      const usedNames = new Map(); let totalSize = 0;
      const CONCURRENCY = 16;
      let currentIndex = 0;
      let lastProgressUpdate = 0;

      async function worker() {
        while (currentIndex < selectedCandidates.length) {
          ensureNotCancelled(job);
          const index = currentIndex++;
          const [sourceUrl, meta] = selectedCandidates[index];
          try {
            const file = await downloadImage(sourceUrl, session, meta, filesDir, usedNames, job.folderMode);
            totalSize += file.size;
            job.progress.downloaded++;
            addToBreakdown(job.progress, meta.sourceDate);
          } catch (error) {
            job.progress.failed++;
            console.warn('Delivery image failed:', job.id, error.message);
          }
          const totalProcessed = job.progress.downloaded + job.progress.failed;
          if (totalProcessed - lastProgressUpdate >= 20 || totalProcessed === selectedCandidates.length) {
            lastProgressUpdate = totalProcessed;
            update(job, { progress: { ...job.progress } });
          }
        }
      }

      await Promise.all(Array.from({ length: Math.min(CONCURRENCY, selectedCandidates.length) }, () => worker()));
      if (selectedCandidates.length && !job.progress.downloaded && !selectedNotices.length) throw new Error('사진을 다운로드하지 못했습니다.');
      const noticeCount = job.includeNotices ? writeNoticeFiles(selectedNotices, filesDir, job.folderMode) : 0;
      ensureNotCancelled(job);
      update(job, { status: 'compressing', progress: { ...job.progress, step: 'ZIP 압축 중' } });
      const zipPath = await makeZip(job, filesDir);
      fs.rmSync(filesDir, { recursive: true, force: true });
      const tokens = issueShareTokens(job);
      job.zipPath = zipPath;
      job.result = { count: job.progress.downloaded, noticeCount, sourceCount: sourcePhotoCount, sourceNoticeCount, sampleMode: job.sampleMode === true, failed: job.progress.failed, size: fs.statSync(zipPath).size, emailSent: false, breakdown: job.progress.breakdown };
      update(job, { status: 'emailing', progress: { ...job.progress, step: '이메일 발송 중' }, result: job.result, expiresAt: job.expiresAt });
      ensureNotCancelled(job);
      const urls = shareUrls(job, tokens);
      await sendResultEmail(job, urls.downloadUrl, urls.deleteUrl);
      job.result.emailSent = true;
      update(job, { status: 'completed', progress: { ...job.progress, step: '이메일 발송 완료' }, result: job.result });
    } catch (error) {
      password = ''; username = '';
      if (error.code === 'JOB_CANCELLED') {
        const currentDir = path.join(jobsDir, job.id);
        if (fs.existsSync(currentDir)) fs.rmSync(currentDir, { recursive: true, force: true });
        update(job, { status: 'cancelled', error: '', result: null, zipPath: '', zipPassword: '', downloadTokenHash: '', deleteTokenHash: '', expiresAt: null, progress: { ...job.progress, step: '관리자가 취소함' } });
      } else {
        console.error('Delivery job failed:', job.id, error.message);
        update(job, { status: 'failed', error: error.message || '작업에 실패했습니다.', progress: { ...job.progress, step: '실패' } });
      }
    }
  }

  async function processTestJob(job) {
    const jobDir = path.join(jobsDir, job.id);
    const filesDir = path.join(jobDir, 'files');
    fs.mkdirSync(filesDir, { recursive: true, mode: 0o700 });
    try {
      update(job, { status: 'collecting', progress: { step: '테스트 사진 10장 찾는 중', found: 0, downloaded: 0, failed: 0 } });
      const session = options.getTestSession();
      if (!session) throw new Error('사용할 수 있는 기존 키즈노트 로그인 세션이 없습니다.');
      ensureNotCancelled(job);
      const rawChildren = Array.isArray(session.children) && session.children.length
        ? session.children
        : [{ id: session.childId, name: '', enrollment: session.enrollment }];
      const seenNames = new Map();
      const children = rawChildren.map((c, idx) => {
        const baseName = sanitizeChildFolderName(c.name, c.id, idx);
        const count = seenNames.get(baseName) || 0;
        seenNames.set(baseName, count + 1);
        const folderName = count > 0 ? `${baseName}_${c.id || count + 1}` : baseName;
        return { ...c, folderName };
      });
      const candidates = new Map();
      for (const child of children) {
        ensureNotCancelled(job);
        const childFolderName = child.folderName;
        for (const collection of [{ name: 'reports', type: 'report' }, { name: 'albums', type: 'album' }]) {
          const items = await options.fetchCollection(child.id, session.cookie, collection.name, { enrollment: child.enrollment || session.enrollment, maxPages: 3 });
          ensureNotCancelled(job);
          for (const item of items) {
            for (const sourceUrl of options.getImageUrls(item)) {
              candidates.set(sourceUrl, {
                sourceDate: options.getDate(item),
                sourceType: collection.type,
                sourcePage: `https://www.kidsnote.com/service/${collection.type}`,
                childFolderName,
                childId: child.id,
                enrollment: child.enrollment || session.enrollment
              });
              if (candidates.size >= 10) break;
            }
            if (candidates.size >= 10) break;
          }
          if (candidates.size >= 10) break;
        }
        if (candidates.size >= 10) break;
      }
      const selected = [...candidates.entries()].slice(0, 10);
      if (!selected.length) throw new Error('기존 세션에서 테스트 사진을 찾지 못했습니다.');
      update(job, { status: 'downloading', progress: { ...job.progress, step: '테스트 사진 다운로드 중', found: selected.length } });
      const usedNames = new Map();
      for (const [sourceUrl, meta] of selected) {
        ensureNotCancelled(job);
        try { await downloadImage(sourceUrl, session, meta, filesDir, usedNames, job.folderMode); job.progress.downloaded++; addToBreakdown(job.progress, meta.sourceDate); }
        catch (error) { job.progress.failed++; console.warn('Delivery test image failed:', error.message); }
      }
      if (!job.progress.downloaded) throw new Error('테스트 사진을 다운로드하지 못했습니다.');
      ensureNotCancelled(job);
      update(job, { status: 'compressing', progress: { ...job.progress, step: '테스트 ZIP 압축 중' } });
      const zipPath = await makeZip(job, filesDir);
      fs.rmSync(filesDir, { recursive: true, force: true });
      const tokens = issueShareTokens(job);
      job.zipPath = zipPath;
      job.result = { count: job.progress.downloaded, failed: job.progress.failed, size: fs.statSync(zipPath).size, emailSent: false, test: true, breakdown: job.progress.breakdown || {} };
      update(job, { status: 'emailing', progress: { ...job.progress, step: '테스트 이메일 발송 중' }, result: job.result, expiresAt: job.expiresAt });
      ensureNotCancelled(job);
      const urls = shareUrls(job, tokens);
      await sendResultEmail(job, urls.downloadUrl, urls.deleteUrl);
      job.result.emailSent = true;
      update(job, { status: 'completed', progress: { ...job.progress, step: '테스트 이메일 발송 완료' }, result: job.result });
    } catch (error) {
      if (error.code === 'JOB_CANCELLED') {
        if (fs.existsSync(jobDir)) fs.rmSync(jobDir, { recursive: true, force: true });
        update(job, { status: 'cancelled', error: '', result: null, zipPath: '', zipPassword: '', downloadTokenHash: '', deleteTokenHash: '', expiresAt: null, progress: { ...job.progress, step: '관리자가 취소함' } });
      } else {
        console.error('Delivery test failed:', job.id, error.message);
        update(job, { status: 'failed', error: error.message || '테스트에 실패했습니다.', progress: { ...job.progress, step: '실패' } });
      }
    }
  }

  app.get('/api/delivery/jobs', requireAdmin, (req, res) => {
    res.setHeader('Cache-Control', 'no-store');
    res.json([...jobs.values()].sort((a, b) => b.createdAt - a.createdAt).slice(0, 100).map(publicJob));
  });

  app.post('/api/delivery/requests', (req, res) => {
    const username = String(req.body?.username || '').trim();
    const password = String(req.body?.password || '');
    const email = String(req.body?.email || '').trim().toLowerCase();
    const year = String(req.body?.year || '').trim();
    const deliveryNote = String(req.body?.deliveryNote || '').trim();
    const includeNotices = req.body?.includeNotices === true;
    const folderMode = String(req.body?.folderMode || 'day');
    const sampleMode = req.body?.sampleMode === true;
    if (!username || username.length > 150 || !password || password.length > 300) return res.status(400).json({ error: '키즈노트 ID와 비밀번호를 확인해 주세요.' });
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) || email.length > 200) return res.status(400).json({ error: '받는 이메일 주소를 확인해 주세요.' });
    if (year && !/^20\d{2}$/.test(year)) return res.status(400).json({ error: '연도를 올바르게 선택해 주세요.' });
    if (deliveryNote.length > 500) return res.status(400).json({ error: '전달 내용은 500자 이내로 입력해 주세요.' });
    if (!['day', 'month'].includes(folderMode)) return res.status(400).json({ error: '폴더 구성을 올바르게 선택해 주세요.' });
    const ip = String(req.get('x-forwarded-for') || req.socket.remoteAddress).split(',')[0].trim();
    const cutoff = Date.now() - 60 * 60 * 1000;
    const attempts = (requestRates.get(ip) || []).filter(value => value > cutoff);
    if (attempts.length >= 3) return res.status(429).json({ error: '한 시간에 최대 3번까지 신청할 수 있습니다.' });
    attempts.push(Date.now()); requestRates.set(ip, attempts);
    const now = Date.now();
    const item = { id: crypto.randomBytes(12).toString('hex'), email, year, deliveryNote, includeNotices, folderMode, sampleMode, status: 'pending', createdAt: now, updatedAt: now, encryptedCredentials: encryptCredentials({ username, password }) };
    requests.set(item.id, item); saveRequests();
    res.status(202).json({ id: item.id, status: item.status, message: '신청이 접수되었습니다. 관리자가 확인 후 진행합니다.' });
  });

  app.get('/api/delivery/requests', requireAdmin, (req, res) => {
    res.setHeader('Cache-Control', 'no-store');
    res.json([...requests.values()].sort((a, b) => b.createdAt - a.createdAt).map(publicRequest));
  });

  function startRequestJob(item) {
    if ([...jobs.values()].some(job => !['completed', 'failed', 'cancelled'].includes(job.status))) {
      const error = new Error('현재 진행 중인 작업이 있습니다. 완료 후 다시 시작해 주세요.');
      error.status = 409;
      throw error;
    }
    let credentials;
    try { credentials = decryptCredentials(item.encryptedCredentials); }
    catch {
      const error = new Error('암호화된 신청 정보를 읽지 못했습니다.');
      error.status = 500;
      throw error;
    }
    const now = Date.now();
    const job = { id: crypto.randomBytes(12).toString('hex'), email: item.email, year: item.year, deliveryNote: item.deliveryNote || '', includeNotices: item.includeNotices === true, folderMode: item.folderMode || 'day', sampleMode: item.sampleMode === true, status: 'queued', createdAt: now, updatedAt: now, expiresAt: null, progress: { step: '대기 중', found: 0, downloaded: 0, failed: 0 }, result: null, error: '', requestId: item.id, encryptedCredentials: item.encryptedCredentials };
    jobs.set(job.id, job);
    item.status = 'accepted';
    item.jobId = job.id;
    item.updatedAt = now;
    saveRequests();
    saveJobs();
    setImmediate(() => processJob(job, credentials.username, credentials.password));
    return job;
  }

  app.post('/api/delivery/sample', (req, res) => {
    const username = String(req.body?.username || '').trim();
    const password = String(req.body?.password || '');
    const email = String(req.body?.email || '').trim().toLowerCase();
    const year = String(req.body?.year || '').trim();
    const deliveryNote = String(req.body?.deliveryNote || '').trim();
    const includeNotices = req.body?.includeNotices === true;
    const folderMode = String(req.body?.folderMode || 'day');
    if (!username || username.length > 150 || !password || password.length > 300) return res.status(400).json({ error: '키즈노트 ID와 비밀번호를 확인해 주세요.' });
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) || email.length > 200) return res.status(400).json({ error: '받는 이메일 주소를 확인해 주세요.' });
    if (year && !/^20\d{2}$/.test(year)) return res.status(400).json({ error: '연도를 올바르게 선택해 주세요.' });
    if (deliveryNote.length > 500) return res.status(400).json({ error: '전달 내용은 500자 이내로 입력해 주세요.' });
    if (!['day', 'month'].includes(folderMode)) return res.status(400).json({ error: '폴더 구성을 올바르게 선택해 주세요.' });
    if ([...jobs.values()].some(item => !['completed', 'failed', 'cancelled'].includes(item.status))) return res.status(409).json({ error: '현재 진행 중인 작업이 있습니다. 완료 후 다시 요청해 주세요.' });
    const ip = `sample:${String(req.get('x-forwarded-for') || req.socket.remoteAddress).split(',')[0].trim()}`;
    const cutoff = Date.now() - 60 * 60 * 1000;
    const attempts = (requestRates.get(ip) || []).filter(value => value > cutoff);
    if (attempts.length >= 3) return res.status(429).json({ error: '샘플 테스트는 한 시간에 최대 3번까지 실행할 수 있습니다.' });
    attempts.push(Date.now()); requestRates.set(ip, attempts);
    const now = Date.now();
    const job = { id: crypto.randomBytes(12).toString('hex'), email, year, deliveryNote, includeNotices, folderMode, sampleMode: true, status: 'queued', createdAt: now, updatedAt: now, expiresAt: null, progress: { step: '1/10 샘플 작업 대기 중', found: 0, downloaded: 0, failed: 0 }, result: null, error: '' };
    jobs.set(job.id, job); saveJobs(); setImmediate(() => processJob(job, username, password));
    res.status(202).json(publicJob(job));
  });

  app.post('/api/delivery/requests/:requestId/accept', requireAdmin, (req, res) => {
    const item = requests.get(req.params.requestId);
    if (!item) return res.status(404).json({ error: '신청을 찾을 수 없습니다.' });
    if (item.status !== 'pending') return res.status(409).json({ error: '이미 처리된 신청입니다.' });
    try { startRequestJob(item); }
    catch (error) { return res.status(error.status || 500).json({ error: error.message }); }
    res.json(publicRequest(item));
  });

  app.post('/api/delivery/requests/:requestId/rerun', requireAdmin, (req, res) => {
    const item = requests.get(req.params.requestId);
    if (!item) return res.status(404).json({ error: '신청을 찾을 수 없습니다.' });
    if (!item.encryptedCredentials || item.status === 'rejected') return res.status(409).json({ error: '다시 실행할 로그인 정보가 남아 있지 않습니다.' });
    try { startRequestJob(item); }
    catch (error) { return res.status(error.status || 500).json({ error: error.message }); }
    res.json(publicRequest(item));
  });

  app.post('/api/delivery/requests/:requestId/reject', requireAdmin, (req, res) => {
    const item = requests.get(req.params.requestId);
    if (!item) return res.status(404).json({ error: '신청을 찾을 수 없습니다.' });
    if (item.status !== 'pending') return res.status(409).json({ error: '이미 처리된 신청입니다.' });
    item.status = 'rejected'; item.updatedAt = Date.now(); delete item.encryptedCredentials;
    saveRequests(); res.json(publicRequest(item));
  });

  app.post('/api/delivery/jobs', requireAdmin, (req, res) => {
    const username = String(req.body?.username || '').trim();
    const password = String(req.body?.password || '');
    const email = String(req.body?.email || '').trim().toLowerCase();
    const year = String(req.body?.year || '').trim();
    if (!username || username.length > 150 || !password || password.length > 300) return res.status(400).json({ error: '키즈노트 ID와 비밀번호를 확인해 주세요.' });
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) || email.length > 200) return res.status(400).json({ error: '받는 이메일 주소를 확인해 주세요.' });
    if (year && !/^20\d{2}$/.test(year)) return res.status(400).json({ error: '연도를 올바르게 선택해 주세요.' });
    if ([...jobs.values()].some(item => !['completed', 'failed', 'cancelled'].includes(item.status))) return res.status(409).json({ error: '현재 진행 중인 작업이 있습니다. 완료 후 다시 요청해 주세요.' });
    const now = Date.now();
    const job = { id: crypto.randomBytes(12).toString('hex'), email, year, status: 'queued', createdAt: now, updatedAt: now, expiresAt: null, progress: { step: '대기 중', found: 0, downloaded: 0, failed: 0 }, result: null, error: '' };
    jobs.set(job.id, job); saveJobs(); setImmediate(() => processJob(job, username, password));
    res.status(202).json(publicJob(job));
  });

  app.post('/api/delivery/test', requireAdmin, (req, res) => {
    const email = String(req.body?.email || '').trim().toLowerCase();
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) return res.status(400).json({ error: '받는 이메일 주소를 확인해 주세요.' });
    if ([...jobs.values()].some(item => !['completed', 'failed', 'cancelled'].includes(item.status))) return res.status(409).json({ error: '현재 진행 중인 작업이 있습니다.' });
    const now = Date.now();
    const job = { id: crypto.randomBytes(12).toString('hex'), email, year: '테스트 10장', status: 'queued', createdAt: now, updatedAt: now, expiresAt: null, progress: { step: '대기 중', found: 0, downloaded: 0, failed: 0 }, result: null, error: '' };
    jobs.set(job.id, job); saveJobs(); setImmediate(() => processTestJob(job));
    res.status(202).json(publicJob(job));
  });

  app.post('/api/delivery/jobs/:jobId/cancel', requireAdmin, (req, res) => {
    const job = jobs.get(req.params.jobId);
    if (!job) return res.status(404).json({ error: '작업을 찾을 수 없습니다.' });
    if (['completed', 'failed', 'cancelled'].includes(job.status)) return res.status(409).json({ error: '이미 종료된 작업입니다.' });
    job.cancelRequested = true;
    job.status = 'cancelled';
    job.updatedAt = Date.now();
    job.progress = { ...(job.progress || {}), step: '관리자가 취소함' };
    saveJobs();

    if (job.requestId && requests.has(job.requestId)) {
      const reqItem = requests.get(job.requestId);
      if (reqItem && reqItem.status === 'accepted') {
        reqItem.status = 'pending';
        reqItem.jobId = '';
        saveRequests();
      }
    }

    const filesDir = path.join(jobsDir, job.id, 'files');
    if (fs.existsSync(filesDir)) {
      try { fs.rmSync(filesDir, { recursive: true, force: true }); } catch {}
    }

    res.json(publicJob(job));
  });

  app.post('/api/delivery/jobs/:jobId/resend', requireAdmin, async (req, res) => {
    const job = jobs.get(req.params.jobId);
    if (!job || job.status !== 'completed' || !job.zipPath || !fs.existsSync(job.zipPath)) return res.status(404).json({ error: '재발송할 완료 ZIP을 찾을 수 없습니다.' });
    const tokens = issueShareTokens(job);
    update(job, { expiresAt: job.expiresAt });
    try {
      const urls = shareUrls(job, tokens);
      await sendResultEmail(job, urls.downloadUrl, urls.deleteUrl);
      res.json({ sent: true, email: job.email, expiresAt: job.expiresAt });
    } catch (error) {
      res.status(502).json({ error: error.message || '이메일 재발송에 실패했습니다.' });
    }
  });

  function findDownload(req) {
    const job = jobs.get(req.params.jobId);
    const hash = crypto.createHash('sha256').update(String(req.params.token)).digest('hex');
    return job && job.downloadTokenHash && hash === job.downloadTokenHash && job.expiresAt >= Date.now() && job.zipPath && fs.existsSync(job.zipPath) ? job : null;
  }

  function findDeletion(req) {
    const job = jobs.get(req.params.jobId);
    const hash = crypto.createHash('sha256').update(String(req.params.token)).digest('hex');
    return job && job.deleteTokenHash && hash === job.deleteTokenHash && job.expiresAt >= Date.now()
      && job.zipPath && fs.existsSync(job.zipPath) ? job : null;
  }

  function deleteSharedZip(job, reason = '사용자가 공유 파일을 삭제함') {
    const expectedDir = path.resolve(jobsDir, job.id);
    const zipPath = path.resolve(job.zipPath);
    if (path.dirname(zipPath) !== expectedDir) throw new Error('삭제할 공유 파일 경로가 올바르지 않습니다.');
    if (fs.existsSync(zipPath)) fs.unlinkSync(zipPath);
    job.result = { ...(job.result || {}), deletedAt: Date.now() };
    update(job, {
      zipPath: '', zipPassword: '', downloadTokenHash: '', deleteTokenHash: '', expiresAt: null,
      result: job.result, progress: { ...(job.progress || {}), step: reason }
    });
  }

  app.get('/delivery/open/:jobId/:token', (req, res) => {
    const job = findDownload(req);
    if (!job) return res.status(404).send('다운로드 링크가 만료되었거나 올바르지 않습니다.');
    const publicBase = String(process.env.PUBLIC_BASE_URL || '').replace(/\/$/, '');
    const fileUrl = `${publicBase}/kidsnote-files/delivery/download/${encodeURIComponent(req.params.jobId)}/${encodeURIComponent(req.params.token)}`;
    const count = Number(job.result?.count) || 0;
    const noticeCount = Number(job.result?.noticeCount) || 0;
    const size = job.result?.size ? `${(job.result.size / 1024 / 1024).toFixed(1)} MB` : '';
    const sampleInfo = job.sampleMode ? `<div class="sample"><b>1/10 샘플 테스트</b><br>전체 사진 ${job.result?.sourceCount || 0}장 중 ${count}장을 추출했습니다.</div>` : '';
    const password = job.zipPassword ? `<div class="password"><small>압축 비밀번호</small><b>${job.zipPassword}</b></div>` : '';
    res.setHeader('Cache-Control', 'private, no-store');
    res.type('html').send(`<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>키즈노트 자료 다운로드</title><style>*{box-sizing:border-box}body{margin:0;background:#f3f8f5;color:#17211b;font:16px/1.6 system-ui,-apple-system,"Noto Sans KR",sans-serif}.card{width:min(480px,calc(100% - 28px));margin:10vh auto;padding:34px;background:#fff;border:1px solid #dbe7df;border-radius:24px;box-shadow:0 20px 60px #173c2512;text-align:center}.icon{display:grid;place-items:center;width:64px;height:64px;margin:0 auto 20px;border-radius:20px;background:#eaf5ee;color:#347a55;font-size:30px}h1{margin:0 0 10px;font-size:27px}p{color:#6e7e74}.info,.password,.sample{margin:20px 0;padding:15px;border-radius:14px;background:#f6f9f7;color:#17211b}.sample{background:#fff7e6;color:#805f18}.password small,.password b{display:block}.password b{font-size:20px;letter-spacing:.04em}.button{display:block;padding:15px 20px;border-radius:13px;background:#347a55;color:#fff;text-decoration:none;font-weight:800}.help{font-size:13px}</style></head><body><main class="card"><div class="icon">↓</div><h1>자료 ZIP이 준비되었습니다</h1><p>아래 버튼을 눌러 기기에 저장하세요.</p><div class="info">사진 <b>${count}장</b>${noticeCount ? ` · 알림장 <b>${noticeCount}개</b>` : ''} · ${size}</div>${sampleInfo}${password}<a class="button" href="${fileUrl}" download>ZIP 다운로드</a><p class="help">다운로드한 파일은 기기의 ‘다운로드’ 폴더에서 확인할 수 있습니다.<br>링크는 7일 후 만료됩니다.</p></main></body></html>`);
  });

  app.get('/delivery/download/:jobId/:token', (req, res) => {
    const job = findDownload(req);
    if (!job) return res.status(404).send('다운로드 링크가 만료되었거나 올바르지 않습니다.');
    const period = job.result?.test ? 'test-10' : `${job.sampleMode ? 'sample-' : ''}${job.year || 'all'}`;
    res.setHeader('Cache-Control', 'private, no-store');
    res.download(job.zipPath, `kidsnote-data-${period}.zip`, { acceptRanges: true, cacheControl: false });
  });

  app.get('/delivery/delete/:jobId/:token', (req, res) => {
    const job = findDeletion(req);
    if (!job) return res.status(404).send('삭제 링크가 만료되었거나 파일이 이미 삭제되었습니다.');
    const publicBase = String(process.env.PUBLIC_BASE_URL || '').replace(/\/$/, '');
    const action = `${publicBase}/kidsnote-files/delivery/delete/${encodeURIComponent(req.params.jobId)}/${encodeURIComponent(req.params.token)}`;
    res.setHeader('Cache-Control', 'private, no-store');
    res.type('html').send(`<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>공유 파일 삭제</title><style>*{box-sizing:border-box}body{margin:0;background:#f7f8f7;color:#17211b;font:16px/1.6 system-ui,-apple-system,"Noto Sans KR",sans-serif}.card{width:min(460px,calc(100% - 28px));margin:12vh auto;padding:34px;border:1px solid #eadede;border-radius:22px;background:#fff;text-align:center;box-shadow:0 18px 50px #173c250a}h1{margin:0 0 12px;font-size:26px}p{color:#6f7973}.delete{width:100%;min-height:52px;margin-top:16px;border:0;border-radius:12px;background:#b83e3e;color:#fff;font:inherit;font-weight:800;cursor:pointer}.help{font-size:13px}</style></head><body><main class="card"><h1>공유 파일을 삭제할까요?</h1><p>삭제하면 다운로드 링크가 즉시 만료되며 복구할 수 없습니다.</p><form method="post" action="${action}"><button class="delete" type="submit">파일 영구 삭제</button></form><p class="help">취소하려면 이 화면을 닫아 주세요.</p></main></body></html>`);
  });

  app.post('/delivery/delete/:jobId/:token', (req, res) => {
    const job = findDeletion(req);
    if (!job) return res.status(404).send('삭제 링크가 만료되었거나 파일이 이미 삭제되었습니다.');
    try { deleteSharedZip(job); }
    catch (error) { return res.status(500).send(error.message || '공유 파일을 삭제하지 못했습니다.'); }
    res.setHeader('Cache-Control', 'private, no-store');
    res.type('html').send('<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>삭제 완료</title><style>body{margin:0;background:#f3f8f5;color:#17211b;font:16px/1.6 system-ui;text-align:center}.card{width:min(440px,calc(100% - 28px));margin:15vh auto;padding:36px;background:#fff;border-radius:22px}h1{color:#347a55}</style></head><body><main class="card"><h1>파일이 삭제되었습니다</h1><p>공유 ZIP과 다운로드 링크가 즉시 폐기되었습니다.</p></main></body></html>');
  });

  readStoredJobs(); readRequests();
  setInterval(() => {
    for (const job of jobs.values()) {
      if (job.expiresAt && job.expiresAt < Date.now() && job.zipPath && fs.existsSync(job.zipPath)) {
        try { deleteSharedZip(job, '7일 만료로 공유 파일을 자동 삭제함'); }
        catch (error) { console.error('Expired delivery cleanup failed:', job.id, error.message); }
      }
    }
  }, 60 * 60 * 1000).unref();
}

module.exports = { startDeliveryService };
