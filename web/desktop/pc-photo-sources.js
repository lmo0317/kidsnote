const crypto = require('crypto');
const archiver = require('archiver');

module.exports = function registerPcPhotoSources(app, dependencies) {
  const { getSavedKidsNoteSession, fetchKidsNoteCollection, getKidsNoteImageUrlsFromItem, getKidsNoteItemDate, getKidsNoteItemTitle } = dependencies;
  const sourceSets = new Map();

  function getSet(session) {
    let set = sourceSets.get(session.token);
    if (!set) {
      set = { expiresAt: 0, items: new Map() };
      sourceSets.set(session.token, set);
    }
    set.expiresAt = Date.now() + 2 * 60 * 60 * 1000;
    return set;
  }

  async function fetchOriginal(item, session) {
    const response = await fetch(item.url, {
      headers: {
        Cookie: session.cookie,
        Accept: 'image/avif,image/webp,image/apng,image/*,*/*;q=0.8',
        ...(session.enrollment ? { 'X-ENROLLMENT': session.enrollment } : {}),
        'User-Agent': 'Mozilla/5.0 KidsNote-PC-Downloader/1.0'
      },
      signal: AbortSignal.timeout(30000)
    });
    if (!response.ok) throw new Error(`원본 사진 응답 오류 (${response.status})`);
    const contentType = response.headers.get('content-type') || 'image/jpeg';
    if (!contentType.startsWith('image/')) throw new Error('원본 응답이 이미지가 아닙니다.');
    const buffer = Buffer.from(await response.arrayBuffer());
    if (buffer.length > 40 * 1024 * 1024) throw new Error('사진 파일이 너무 큽니다.');
    return { buffer, contentType };
  }

  app.get('/api/pc-photo-sources', async (req, res) => {
    const session = getSavedKidsNoteSession(req);
    if (!session) return res.status(401).json({ error: '먼저 키즈노트 계정을 연결해 주세요.' });
    const year = String(req.query.year || '').trim();
    if (!/^20\d{2}$/.test(year)) return res.status(400).json({ error: '조회할 연도를 확인해 주세요.' });
    try {
      const found = new Map();
      const rawChildren = Array.isArray(session.children) && session.children.length
        ? session.children
        : [{ id: session.childId, name: '', enrollment: session.enrollment }];
      const seenNames = new Map();
      const children = rawChildren.map((c, idx) => {
        let clean = String(c.name || '').replace(/[\\/:*?"<>|\r\n\t]/g, '_').trim();
        if (!clean) clean = c.id ? `자녀_${c.id}` : `자녀_${idx + 1}`;
        const count = seenNames.get(clean) || 0;
        seenNames.set(clean, count + 1);
        const folderName = count > 0 ? `${clean}_${c.id || count + 1}` : clean;
        return { ...c, folderName };
      });
      for (const child of children) {
        for (const collection of ['reports', 'albums']) {
          const entries = await fetchKidsNoteCollection(child.id, session.cookie, collection, { enrollment: child.enrollment || session.enrollment, maxPages: 50 });
          for (const entry of entries) {
            const takenAt = getKidsNoteItemDate(entry);
            if (!takenAt.startsWith(year)) continue;
            const sourceTitle = getKidsNoteItemTitle(entry);
            for (const url of getKidsNoteImageUrlsFromItem(entry)) {
              const id = crypto.createHash('sha256').update(url).digest('hex');
              found.set(id, { id, url, takenAt, sourceTitle, childFolderName: child.folderName, childName: child.name, childId: child.id });
            }
          }
        }
      }
      const set = getSet(session);
      found.forEach((item, id) => set.items.set(id, item));
      const photos = Array.from(found.values())
        .map(({ id, takenAt, sourceTitle, childFolderName, childName }) => ({ id, takenAt, sourceTitle, childFolderName, childName }))
        .sort((a, b) => String(b.takenAt).localeCompare(String(a.takenAt)) || b.id.localeCompare(a.id));
      const dates = Array.from(new Set(photos.map(photo => photo.takenAt.slice(0, 10)).filter(Boolean))).sort().reverse();
      res.setHeader('Cache-Control', 'no-store');
      res.json({ photos, dates, totalCount: photos.length, year });
    } catch (error) {
      res.status(error.status || 502).json({ error: error.message || '키즈노트 사진 목록을 불러오지 못했습니다.' });
    }
  });

  app.get('/api/pc-photo-sources/:id/file', async (req, res) => {
    const session = getSavedKidsNoteSession(req);
    const item = session && sourceSets.get(session.token)?.items.get(req.params.id);
    if (!session || !item) return res.status(404).json({ error: '사진 목록을 다시 불러와 주세요.' });
    try {
      const original = await fetchOriginal(item, session);
      res.setHeader('Cache-Control', 'private, max-age=300');
      res.type(original.contentType);
      if (req.query.download === '1') res.setHeader('Content-Disposition', `attachment; filename="kidsnote-${item.takenAt.slice(0, 10)}-${item.id.slice(0, 8)}.jpg"`);
      res.send(original.buffer);
    } catch (error) {
      res.status(502).json({ error: error.message || '사진 전송에 실패했습니다.' });
    }
  });

  app.get('/api/pc-photo-download.zip', async (req, res) => {
    const session = getSavedKidsNoteSession(req);
    if (!session) return res.status(401).json({ error: '먼저 키즈노트 계정을 연결해 주세요.' });
    const date = String(req.query.date || '').trim();
    const month = String(req.query.month || '').trim();
    const year = String(req.query.year || '').trim();
    if (!/^20\d{2}-\d{2}-\d{2}$/.test(date) && !/^20\d{2}-(0[1-9]|1[0-2])$/.test(month) && !/^20\d{2}$/.test(year)) return res.status(400).json({ error: '다운로드 연도, 월 또는 날짜를 확인해 주세요.' });
    const range = date || month || year;
    const items = Array.from(sourceSets.get(session.token)?.items.values() || []).filter(item => item.takenAt.startsWith(range));
    if (!items.length) return res.status(404).json({ error: '다운로드할 사진이 없습니다.' });
    res.setHeader('Content-Type', 'application/zip');
    res.setHeader('Content-Disposition', `attachment; filename="kidsnote-${range}.zip"`);
    const archive = archiver('zip', { zlib: { level: 0 } });
    archive.on('error', error => res.destroy(error));
    archive.pipe(res);
    try {
      let index = 0;
      for (const item of items) {
        index++;
        const original = await fetchOriginal(item, session);
        const extension = /png/i.test(original.contentType) ? '.png' : /webp/i.test(original.contentType) ? '.webp' : '.jpg';
        const itemDate = item.takenAt.slice(0, 10);
        const match = itemDate.match(/^(\d{4})-(\d{2})-(\d{2})/);
        const dateFolder = match ? `${match[1]}년/${match[2]}월/${match[3]}일` : itemDate;
        const entryPath = item.childFolderName ? `${item.childFolderName}/${dateFolder}` : dateFolder;
        archive.append(original.buffer, { name: `${entryPath}/kidsnote-${itemDate}-${String(index).padStart(4, '0')}${extension}` });
      }
      await archive.finalize();
    } catch (error) {
      archive.abort();
      if (!res.headersSent) res.status(502).json({ error: error.message || 'ZIP 파일 생성에 실패했습니다.' });
      else res.destroy(error);
    }
  });

  setInterval(() => {
    const now = Date.now();
    for (const [token, set] of sourceSets) if (set.expiresAt < now) sourceSets.delete(token);
  }, 10 * 60 * 1000).unref();
};
