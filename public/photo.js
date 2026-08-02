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
const progressPanel = document.getElementById('progress-panel');
const progressTitle = document.getElementById('progress-title');
const progressCount = document.getElementById('progress-count');
const progressFill = document.getElementById('progress-fill');
const progressDetail = document.getElementById('progress-detail');
const photoCount = document.getElementById('photo-count');
const photoSize = document.getElementById('photo-size');
const yearPickerModal = document.getElementById('year-picker-modal');
const yearPickerClose = document.getElementById('year-picker-close');
const yearOptions = document.getElementById('year-options');
const toast = document.getElementById('toast');

let isConnected = false;
let activeJobId = '';
let pendingBackupYear = '';
let yearCounts = {};
let loginReturnFocus = null;

document.addEventListener('DOMContentLoaded', () => {
  btnStartBackup.addEventListener('click', openYearPicker);
  btnRefresh.addEventListener('click', refreshAll);
  btnKidsNoteSession.addEventListener('click', openKidsNoteLogin);
  kidsNoteLoginClose.addEventListener('click', closeKidsNoteLogin);
  kidsNoteLoginModal.addEventListener('click', event => {
    if (event.target === kidsNoteLoginModal) closeKidsNoteLogin();
  });
  kidsNoteLoginForm.addEventListener('submit', loginKidsNote);
  yearPickerClose.addEventListener('click', closeYearPicker);
  yearPickerModal.addEventListener('click', event => {
    if (event.target === yearPickerModal) closeYearPicker();
  });
  document.addEventListener('keydown', event => {
    if (event.key !== 'Escape') return;
    if (!yearPickerModal.classList.contains('hidden')) closeYearPicker();
    else if (!kidsNoteLoginModal.classList.contains('hidden')) closeKidsNoteLogin();
  });
  refreshAll();
  lucide.createIcons();
});

async function refreshAll() {
  btnRefresh.disabled = true;
  await Promise.all([refreshSession(), loadSummary()]);
  btnRefresh.disabled = false;
  lucide.createIcons();
}

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
  } catch {
    isConnected = false;
    sessionState.classList.add('disconnected');
    sessionState.innerHTML = '<i data-lucide="link-2-off"></i><span>키즈노트 연결 상태를 확인하지 못했습니다</span>';
  }
}

async function loadSummary() {
  try {
    const response = await fetch('/api/photos?limit=1', { cache: 'no-store' });
    const result = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(result.error || '백업 현황을 불러오지 못했습니다.');
    yearCounts = result.yearCounts || {};
    photoCount.textContent = String(result.allCount || 0);
    photoSize.textContent = formatBytes(result.totalSize || 0);
  } catch (error) {
    showToast(error.message);
  }
}

function openYearPicker() {
  if (activeJobId) return;
  renderYearOptions();
  yearPickerModal.classList.remove('hidden');
  yearPickerModal.setAttribute('aria-hidden', 'false');
  document.body.classList.add('modal-open');
}

function closeYearPicker() {
  yearPickerModal.classList.add('hidden');
  yearPickerModal.setAttribute('aria-hidden', 'true');
  if (kidsNoteLoginModal.classList.contains('hidden')) document.body.classList.remove('modal-open');
}

function renderYearOptions() {
  const currentYear = new Date().getFullYear();
  const years = new Set(Object.keys(yearCounts).filter(year => /^\d{4}$/.test(year)));
  years.add(String(currentYear));
  yearOptions.innerHTML = '';
  [...years].sort((a, b) => b.localeCompare(a)).forEach(year => {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'year-option';
    button.innerHTML = `<span><strong>${year}년</strong><small>현재 백업 ${Number(yearCounts[year] || 0).toLocaleString()}장</small></span><i data-lucide="download"></i>`;
    button.addEventListener('click', () => chooseBackupYear(year));
    yearOptions.appendChild(button);
  });
  lucide.createIcons();
}

function chooseBackupYear(year) {
  pendingBackupYear = year;
  closeYearPicker();
  if (!isConnected) {
    showToast('선택한 연도를 백업하려면 먼저 로그인해 주세요.');
    openKidsNoteLogin();
    return;
  }
  startKidsNoteBackup(year);
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
    const year = pendingBackupYear;
    pendingBackupYear = '';
    if (year) startKidsNoteBackup(year);
    else showToast('키즈노트 로그인이 연결되었습니다.');
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

async function startKidsNoteBackup(year) {
  btnStartBackup.disabled = true;
  progressPanel.classList.remove('hidden');
  setProgress({ title: `${year}년 사진 확인 준비 중`, found: 0, processed: 0, saved: 0, skipped: 0, failed: 0 });

  try {
    const response = await fetch('/api/photos/kidsnote-backup/start', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ year })
    });
    const result = await response.json().catch(() => ({}));
    if (!response.ok || !result.jobId) throw new Error(result.error || '키즈노트 사진 백업을 시작하지 못했습니다.');
    activeJobId = result.jobId;
    await pollBackupJob(result.jobId, year);
  } catch (error) {
    activeJobId = '';
    btnStartBackup.disabled = false;
    setProgress({ title: '백업 실패', detail: error.message, failed: 1 });
    showToast(error.message);
  }
}

async function pollBackupJob(jobId, year) {
  for (let attempt = 0; attempt < 720; attempt++) {
    await new Promise(resolve => setTimeout(resolve, 2000));
    const response = await fetch(`/api/photos/kidsnote-backup/jobs/${encodeURIComponent(jobId)}`, { cache: 'no-store' });
    const status = await response.json().catch(() => ({}));
    if (!response.ok || status.status === 'failed') throw new Error(status.error || '키즈노트 사진 백업에 실패했습니다.');
    setProgress({ title: `${year}년 사진 백업 중`, ...(status.progress || {}) });
    if (status.status === 'completed') {
      activeJobId = '';
      const result = status.result || {};
      setProgress({ title: `${year}년 백업 완료 · ZIP 다운로드 시작`, ...result, processed: result.found || 0 });
      btnStartBackup.disabled = false;
      await loadSummary();
      showToast(`${year}년 사진을 압축해 다운로드합니다.`);
      window.location.assign(`/api/photos/download-all?year=${encodeURIComponent(year)}`);
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
  const ratio = found > 0 ? Math.min(100, Math.round((processed / found) * 100)) : 4;
  progressTitle.textContent = progress.title || '백업 진행 중';
  progressCount.textContent = found > 0 ? `${processed} / ${found}` : '사진 확인 중';
  progressFill.style.width = `${ratio}%`;
  progressDetail.textContent = progress.detail || `새로 저장 ${saved}개 · 기존 사진 ${skipped}개 · 실패 ${failed}개`;
}

function showToast(message) {
  toast.textContent = message;
  toast.classList.add('show');
  clearTimeout(showToast.timer);
  showToast.timer = setTimeout(() => toast.classList.remove('show'), 3200);
}

function formatBytes(bytes) {
  if (!Number.isFinite(Number(bytes)) || Number(bytes) <= 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  const index = Math.min(units.length - 1, Math.floor(Math.log(Number(bytes)) / Math.log(1024)));
  return `${(Number(bytes) / (1024 ** index)).toFixed(index > 1 ? 1 : 0)} ${units[index]}`;
}
