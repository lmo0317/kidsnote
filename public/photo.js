const sessionState = document.getElementById('session-state');
const btnStartBackup = document.getElementById('btn-start-backup');
const btnRefresh = document.getElementById('btn-refresh');
const btnKidsNoteSession = document.getElementById('btn-kidsnote-session');
const kidsNoteLoginModal = document.getElementById('kidsnote-login-modal');
const kidsNoteLoginClose = document.getElementById('kidsnote-login-close');
const kidsNoteLoginForm = document.getElementById('kidsnote-login-form');
const kidsNoteUsername = document.getElementById('kidsnote-username');
const kidsNotePassword = document.getElementById('kidsnote-password');
const kidsNoteLoginError = document.getElementById('kidsnote-login-error');
const btnKidsNoteLogin = document.getElementById('btn-kidsnote-login');
const extraUrl = document.getElementById('extra-url');
const progressPanel = document.getElementById('progress-panel');
const progressTitle = document.getElementById('progress-title');
const progressCount = document.getElementById('progress-count');
const progressFill = document.getElementById('progress-fill');
const progressDetail = document.getElementById('progress-detail');
const gallery = document.getElementById('gallery');
const emptyState = document.getElementById('empty-state');
const photoCount = document.getElementById('photo-count');
const photoSize = document.getElementById('photo-size');
const searchInput = document.getElementById('search-input');
const sortSelect = document.getElementById('sort-select');
const btnLoadMore = document.getElementById('btn-load-more');
const yearFilter = document.getElementById('year-filter');
const btnDownloadAll = document.getElementById('btn-download-all');
const toast = document.getElementById('toast');
const photoViewer = document.getElementById('photo-viewer');
const viewerImage = document.getElementById('viewer-image');
const viewerTitle = document.getElementById('viewer-title');
const viewerDetail = document.getElementById('viewer-detail');
const viewerClose = document.getElementById('viewer-close');

let photos = [];
let isConnected = false;
let activeJobId = '';
let photoOffset = 0;
let hasMorePhotos = false;
let viewerHistoryActive = false;
let selectedYear = '';
let yearCounts = {};
const PHOTO_PAGE_SIZE = 80;
let loginReturnFocus = null;

document.addEventListener('DOMContentLoaded', () => {
  btnStartBackup.addEventListener('click', startKidsNoteBackup);
  btnRefresh.addEventListener('click', () => {
    refreshSession();
    loadPhotos({ reset: true });
  });
  btnKidsNoteSession.addEventListener('click', openKidsNoteLogin);
  kidsNoteLoginClose.addEventListener('click', closeKidsNoteLogin);
  kidsNoteLoginModal.addEventListener('click', (event) => {
    if (event.target === kidsNoteLoginModal) closeKidsNoteLogin();
  });
  kidsNoteLoginForm.addEventListener('submit', loginKidsNote);
  searchInput.addEventListener('input', debounce(() => loadPhotos({ reset: true }), 250));
  sortSelect.addEventListener('change', () => loadPhotos({ reset: true }));
  btnLoadMore.addEventListener('click', () => loadPhotos({ reset: false }));
  btnDownloadAll.addEventListener('click', downloadAllPhotos);
  viewerClose.addEventListener('click', closePhotoViewer);
  photoViewer.addEventListener('click', (event) => {
    if (event.target === photoViewer) closePhotoViewer();
  });
  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && !kidsNoteLoginModal.classList.contains('hidden')) {
      closeKidsNoteLogin();
      return;
    }
    if (event.key === 'Escape' && !photoViewer.classList.contains('hidden')) {
      closePhotoViewer();
    }
  });
  window.addEventListener('popstate', () => {
    if (!photoViewer.classList.contains('hidden') && viewerHistoryActive) {
      closePhotoViewer({ fromHistory: true });
    }
  });
  refreshSession();
  loadPhotos({ reset: true });
  lucide.createIcons();
});

async function refreshSession() {
  try {
    const response = await fetch('/api/photo-kidsnote/session', { cache: 'no-store' });
    const session = await response.json().catch(() => ({}));
    isConnected = response.ok && session.connected === true;
    sessionState.classList.toggle('connected', isConnected);
    sessionState.classList.toggle('disconnected', !isConnected);
    sessionState.innerHTML = isConnected
      ? '<i data-lucide="link"></i><span>키즈노트 로그인 연결됨</span>'
      : '<i data-lucide="link-2-off"></i><span>키즈노트 로그인이 필요합니다</span>';
    btnStartBackup.disabled = !isConnected || Boolean(activeJobId);
    if (isConnected && !kidsNoteLoginModal.classList.contains('hidden')) closeKidsNoteLogin();
  } catch {
    isConnected = false;
    sessionState.classList.add('disconnected');
    sessionState.innerHTML = '<i data-lucide="link-2-off"></i><span>키즈노트 연결 상태를 확인하지 못했습니다</span>';
    btnStartBackup.disabled = true;
  }
  lucide.createIcons();
}
function openKidsNoteLogin() {
  loginReturnFocus = document.activeElement;
  kidsNoteLoginError.textContent = '';
  kidsNoteLoginError.classList.add('hidden');
  kidsNoteLoginModal.classList.remove('hidden');
  kidsNoteLoginModal.setAttribute('aria-hidden', 'false');
  document.body.classList.add('modal-open');
  requestAnimationFrame(() => kidsNoteUsername.focus());
}

function closeKidsNoteLogin() {
  kidsNoteLoginModal.classList.add('hidden');
  kidsNoteLoginModal.setAttribute('aria-hidden', 'true');
  document.body.classList.remove('modal-open');
  kidsNotePassword.value = '';
  if (loginReturnFocus instanceof HTMLElement) loginReturnFocus.focus();
}

async function loginKidsNote(event) {
  event.preventDefault();
  const username = kidsNoteUsername.value.trim();
  const password = kidsNotePassword.value;
  if (!username || !password) {
    showLoginError('키즈노트 아이디와 비밀번호를 입력해 주세요.');
    return;
  }

  btnKidsNoteLogin.disabled = true;
  btnKidsNoteLogin.innerHTML = '<i data-lucide="loader-circle" class="spin"></i><span>로그인 확인 중</span>';
  kidsNoteLoginError.classList.add('hidden');
  lucide.createIcons();

  try {
    const response = await fetch('/api/photo-kidsnote/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password })
    });
    const result = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(result.error || '키즈노트 로그인에 실패했습니다.');

    isConnected = true;
    kidsNotePassword.value = '';
    closeKidsNoteLogin();
    await refreshSession();
    showToast('키즈노트 로그인이 연결되었습니다.');
  } catch (error) {
    kidsNotePassword.value = '';
    showLoginError(error.message);
    kidsNotePassword.focus();
  } finally {
    btnKidsNoteLogin.disabled = false;
    btnKidsNoteLogin.innerHTML = '<i data-lucide="log-in"></i><span>로그인하고 사진 백업 연결</span>';
    lucide.createIcons();
  }
}

function showLoginError(message) {
  kidsNoteLoginError.textContent = message;
  kidsNoteLoginError.classList.remove('hidden');
}

async function loadPhotos({ reset = true } = {}) {
  try {
    if (reset) {
      photoOffset = 0;
      photos = [];
      gallery.innerHTML = '';
    }
    btnLoadMore.disabled = true;
    const params = new URLSearchParams({
      sort: sortSelect.value,
      q: searchInput.value.trim(),
      year: selectedYear,
      offset: String(photoOffset),
      limit: String(PHOTO_PAGE_SIZE)
    });
    const response = await fetch(`/api/photos?${params.toString()}`, { cache: 'no-store' });
    const result = await response.json();
    if (!response.ok) throw new Error(result.error || '사진 목록을 불러오지 못했습니다.');
    const nextPhotos = Array.isArray(result.photos) ? result.photos : [];
    photos = reset ? nextPhotos : [...photos, ...nextPhotos];
    photoOffset = photos.length;
    hasMorePhotos = result.hasMore === true;
    yearCounts = result.yearCounts || {};
    photoCount.textContent = String(result.totalCount || photos.length);
    photoSize.textContent = formatBytes(result.totalSize || photos.reduce((sum, photo) => sum + (photo.size || 0), 0));
    renderYearFilter();
    btnDownloadAll.disabled = photos.length === 0;
    renderPhotos();
    btnLoadMore.classList.toggle('hidden', !hasMorePhotos);
    btnLoadMore.disabled = false;
  } catch (error) {
    btnLoadMore.disabled = false;
    showToast(error.message);
  }
}

function downloadAllPhotos() {
  if (!photos.length) {
    showToast('다운로드할 사진이 없습니다.');
    return;
  }
  btnDownloadAll.disabled = true;
  btnDownloadAll.innerHTML = '<i data-lucide="loader-circle" class="spin"></i><span>압축 파일 준비 중</span>';
  lucide.createIcons();
  window.location.href = '/api/photos/download-all';
  setTimeout(() => {
    btnDownloadAll.disabled = false;
    btnDownloadAll.innerHTML = '<i data-lucide="archive"></i><span>전체 사진 압축 다운로드</span>';
    lucide.createIcons();
  }, 1500);
}

async function startKidsNoteBackup() {
  if (!isConnected) {
    showToast('먼저 키즈노트에 로그인해 주세요.');
    openKidsNoteLogin();
    return;
  }
  btnStartBackup.disabled = true;
  setProgress({ title: '백업 작업 시작 중', found: 0, processed: 0, saved: 0, skipped: 0, failed: 0 });
  progressPanel.classList.remove('hidden');

  try {
    const response = await fetch('/api/photos/kidsnote-backup/start', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ extraUrl: extraUrl.value.trim() })
    });
    const result = await response.json().catch(() => ({}));
    if (!response.ok || !result.jobId) throw new Error(result.error || '키즈노트 사진 백업을 시작하지 못했습니다.');
    activeJobId = result.jobId;
    await pollBackupJob(result.jobId);
  } catch (error) {
    activeJobId = '';
    btnStartBackup.disabled = !isConnected;
    setProgress({ title: '백업 실패', detail: error.message, failed: 1 });
    showToast(error.message);
  }
}

async function pollBackupJob(jobId) {
  for (let attempt = 0; attempt < 720; attempt++) {
    await new Promise(resolve => setTimeout(resolve, 2000));
    const response = await fetch(`/api/photos/kidsnote-backup/jobs/${encodeURIComponent(jobId)}`, { cache: 'no-store' });
    const status = await response.json().catch(() => ({}));
    if (!response.ok || status.status === 'failed') {
      throw new Error(status.error || '키즈노트 사진 백업에 실패했습니다.');
    }
    setProgress({ title: '키즈노트 사진 백업 중', ...(status.progress || {}) });
    if (status.status === 'completed') {
      activeJobId = '';
      const result = status.result || {};
      setProgress({ title: '백업 완료', ...result, processed: result.found || 0 });
      showToast(`새 사진 ${result.saved || 0}개를 저장했습니다.`);
      btnStartBackup.disabled = !isConnected;
      await loadPhotos({ reset: true });
      return;
    }
  }
  throw new Error('키즈노트 사진 백업 시간이 초과되었습니다.');
}

function setProgress(progress) {
  const found = Number(progress.found) || 0;
  const processed = Number(progress.processed) || 0;
  const saved = Number(progress.saved) || 0;
  const skipped = Number(progress.skipped) || 0;
  const failed = Number(progress.failed) || 0;
  const pagesVisited = Number(progress.pagesVisited) || 0;
  const failedPages = Number(progress.failedPages) || 0;
  const ratio = found > 0 ? Math.min(100, Math.round((processed / found) * 100)) : 4;

  progressTitle.textContent = progress.title || '백업 진행 중';
  progressCount.textContent = found > 0 ? `${processed} / ${found}` : `${pagesVisited}개 페이지 확인`;
  progressFill.style.width = `${ratio}%`;

  const current = progress.currentPage || progress.currentImage || progress.detail || '';
  progressDetail.textContent = `페이지 ${pagesVisited}개 · 저장 ${saved}개 · 중복/제외 ${skipped}개 · 다운로드 실패 ${failed}개 · 페이지 스킵 ${failedPages}개${current ? ` · ${shorten(current, 80)}` : ''}`;
}

function renderYearFilter() {
  const years = Object.keys(yearCounts).filter(year => /^\d{4}$/.test(year)).sort((a, b) => b.localeCompare(a));
  yearFilter.innerHTML = '';
  if (!years.length) {
    yearFilter.classList.add('hidden');
    return;
  }
  yearFilter.classList.remove('hidden');

  const options = [['', '전체'], ...years.map(year => [year, year])];
  for (const [year, label] of options) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = year === selectedYear ? 'active' : '';
    button.textContent = label;
    button.addEventListener('click', () => {
      if (selectedYear === year) return;
      selectedYear = year;
      loadPhotos({ reset: true });
    });
    yearFilter.appendChild(button);
  }
}

function renderPhotos() {
  gallery.innerHTML = '';
  emptyState.classList.toggle('hidden', photos.length > 0);

  for (const photo of photos) {
    const card = document.createElement('article');
    card.className = 'photo-card';

    const image = document.createElement('img');
    image.className = 'photo-thumb';
    image.loading = 'lazy';
    image.src = `/api/photos/${encodeURIComponent(photo.id)}/thumb`;
    image.alt = photo.originalName;
    image.tabIndex = 0;
    image.role = 'button';
    image.title = '크게 보기';
    image.addEventListener('click', () => openPhotoViewer(photo));
    image.addEventListener('keydown', (event) => {
      if (event.key === 'Enter' || event.key === ' ') {
        event.preventDefault();
        openPhotoViewer(photo);
      }
    });

    const meta = document.createElement('div');
    meta.className = 'photo-meta';

    const name = document.createElement('p');
    name.className = 'photo-name';
    name.title = photo.originalName;
    name.textContent = photo.originalName;

    const detail = document.createElement('div');
    detail.className = 'photo-detail';
    const sourceDate = photo.takenAt ? formatDate(photo.takenAt) : '글 날짜 없음';
    detail.textContent = `${sourceDate}${photo.sourceTitle ? ` · ${photo.sourceTitle}` : ''} · ${formatBytes(photo.size)}`;

    const source = document.createElement('span');
    source.className = 'photo-source';
    source.textContent = photo.sourceType === 'album' ? '추억앨범' : '추억알림장';

    const actions = document.createElement('div');
    actions.className = 'photo-actions';

    const download = document.createElement('a');
    download.href = `/api/photos/${encodeURIComponent(photo.id)}/file?download=1`;
    download.innerHTML = '<i data-lucide="download"></i><span>다운로드</span>';

    const remove = document.createElement('button');
    remove.type = 'button';
    remove.className = 'danger';
    remove.title = '삭제';
    remove.innerHTML = '<i data-lucide="trash-2"></i>';
    remove.addEventListener('click', () => deletePhoto(photo));

    meta.appendChild(name);
    meta.appendChild(detail);
    meta.appendChild(source);
    actions.appendChild(download);
    actions.appendChild(remove);
    card.appendChild(image);
    card.appendChild(meta);
    card.appendChild(actions);
    gallery.appendChild(card);
  }

  lucide.createIcons();
}

function openPhotoViewer(photo) {
  const sourceDate = photo.takenAt ? formatDate(photo.takenAt) : '글 날짜 없음';
  const wasClosed = photoViewer.classList.contains('hidden');
  viewerImage.src = `/api/photos/${encodeURIComponent(photo.id)}/file`;
  viewerImage.alt = photo.originalName || '백업 사진';
  viewerTitle.textContent = photo.originalName || '백업 사진';
  viewerDetail.textContent = `${sourceDate}${photo.sourceTitle ? ` · ${photo.sourceTitle}` : ''} · ${photo.sourceType === 'album' ? '추억앨범' : '추억알림장'}`;
  if (wasClosed) {
    window.history.pushState({ photoViewer: true }, '', window.location.href);
    viewerHistoryActive = true;
  }
  photoViewer.classList.remove('hidden');
  photoViewer.setAttribute('aria-hidden', 'false');
  document.body.classList.add('viewer-open');
  viewerClose.focus();
}

function closePhotoViewer({ fromHistory = false } = {}) {
  const shouldRestoreHistory = viewerHistoryActive && !fromHistory;
  photoViewer.classList.add('hidden');
  photoViewer.setAttribute('aria-hidden', 'true');
  document.body.classList.remove('viewer-open');
  viewerImage.removeAttribute('src');
  viewerHistoryActive = false;
  if (shouldRestoreHistory) {
    window.history.back();
  }
}

async function deletePhoto(photo) {
  if (!confirm(`"${photo.originalName}" 사진을 삭제할까요?`)) return;
  try {
    const response = await fetch(`/api/photos/${encodeURIComponent(photo.id)}`, { method: 'DELETE' });
    const result = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(result.error || '사진을 삭제하지 못했습니다.');
    showToast('사진을 삭제했습니다.');
    await loadPhotos({ reset: true });
  } catch (error) {
    showToast(error.message);
  }
}

function formatBytes(value) {
  const bytes = Number(value) || 0;
  if (bytes < 1024) return `${bytes} B`;
  const units = ['KB', 'MB', 'GB', 'TB'];
  let size = bytes / 1024;
  let unit = units.shift();
  while (size >= 1024 && units.length) {
    size /= 1024;
    unit = units.shift();
  }
  return `${size.toFixed(size >= 10 ? 1 : 2)} ${unit}`;
}

function formatDate(value) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '';
  return new Intl.DateTimeFormat('ko-KR', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  }).format(date);
}

function shorten(value, maxLength) {
  const text = String(value || '');
  return text.length > maxLength ? `${text.slice(0, maxLength - 1)}...` : text;
}

function showToast(message) {
  toast.textContent = message;
  toast.classList.add('show');
  clearTimeout(showToast.timer);
  showToast.timer = setTimeout(() => toast.classList.remove('show'), 2600);
}

function debounce(callback, wait) {
  let timer;
  return (...args) => {
    clearTimeout(timer);
    timer = setTimeout(() => callback(...args), wait);
  };
}
