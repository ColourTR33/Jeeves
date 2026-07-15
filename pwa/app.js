/**
 * Jeeves Time — PWA Timer App
 *
 * Uses PouchDB for local-first storage with live replication to CouchDB.
 * Documents:
 *   type: "project"     — synced from desktop app
 *   type: "time_entry"  — completed time entries
 *   type: "running_entry" — currently active timer (one per device)
 */

// ─── State ──────────────────────────────────────────────────────────────────────

let localDb = null;
let remoteDb = null;
let syncHandler = null;
let timerInterval = null;
let timerStartTime = null;
let timerPausedElapsed = 0; // ms accumulated before current resume
let isPaused = false;
const DEVICE_ID = 'pwa-' + (localStorage.getItem('deviceId') || generateId());
localStorage.setItem('deviceId', DEVICE_ID.replace('pwa-', ''));

// ─── Init ───────────────────────────────────────────────────────────────────────

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', init);
} else {
  init();
}

function init() {
  registerServiceWorker();
  loadSettings();
  setupEventListeners();
  initDb();
}

function registerServiceWorker() {
  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register('sw.js').catch(() => {});
  }
}

// ─── Settings ───────────────────────────────────────────────────────────────────

function loadSettings() {
  const url = localStorage.getItem('couchDbUrl') || '';
  const user = localStorage.getItem('couchDbUser') || '';
  const pass = localStorage.getItem('couchDbPass') || '';
  document.getElementById('settings-url').value = url;
  document.getElementById('settings-user').value = user;
  document.getElementById('settings-pass').value = pass;
}

function saveSettings() {
  const url = document.getElementById('settings-url').value.trim();
  const user = document.getElementById('settings-user').value.trim();
  const pass = document.getElementById('settings-pass').value.trim();
  localStorage.setItem('couchDbUrl', url);
  localStorage.setItem('couchDbUser', user);
  localStorage.setItem('couchDbPass', pass);

  // Visual feedback
  const btn = document.getElementById('btn-save-settings');
  btn.textContent = 'Saved! Connecting...';
  btn.disabled = true;
  setTimeout(() => { btn.textContent = 'Save & Connect'; btn.disabled = false; }, 2000);

  initDb();
}

// ─── Database ───────────────────────────────────────────────────────────────────

function initDb() {
  // Local PouchDB (always available, even offline)
  localDb = new PouchDB('jeeves-time');

  const url = localStorage.getItem('couchDbUrl');
  const user = localStorage.getItem('couchDbUser');
  const pass = localStorage.getItem('couchDbPass');

  if (url) {
    try {
      const remoteUrl = user && pass
        ? url.replace('://', `://${encodeURIComponent(user)}:${encodeURIComponent(pass)}@`)
        : url;

      remoteDb = new PouchDB(remoteUrl);

      // Cancel existing sync
      if (syncHandler) syncHandler.cancel();

      // Bi-directional live replication
      syncHandler = localDb.sync(remoteDb, {
        live: true,
        retry: true
      }).on('change', () => {
        loadProjects();
        loadTodayEntries();
      }).on('active', () => {
        setSyncStatus('Syncing...', '');
      }).on('paused', () => {
        setSyncStatus('Connected', 'connected');
        // Reload data when sync pauses (initial replication done)
        loadProjects();
        loadTodayEntries();
      }).on('error', (err) => {
        setSyncStatus('Sync error', 'error');
        console.error('Sync error:', err);
      });

      setSyncStatus('Connected', 'connected');
    } catch (e) {
      setSyncStatus('Connection failed', 'error');
    }
  } else {
    setSyncStatus('Not configured', '');
  }

  loadProjects();
  loadTodayEntries();
  checkForRunningEntry();
}

function setSyncStatus(text, className) {
  const el = document.getElementById('sync-status');
  el.textContent = text;
  el.className = 'sync-status ' + className;
}

// ─── Projects ───────────────────────────────────────────────────────────────────

async function loadProjects() {
  try {
    const result = await localDb.allDocs({ include_docs: true });
    const projects = result.rows
      .map(r => r.doc)
      .filter(d => d && d.type === 'project')
      .sort((a, b) => (a.name || '').localeCompare(b.name || ''));

    console.log('[Jeeves] loadProjects: total docs=' + result.rows.length + ', projects found=' + projects.length);
    if (projects.length === 0 && result.rows.length > 0) {
      // Debug: show what types we have
      const types = result.rows.map(r => r.doc && r.doc.type).filter(Boolean);
      console.log('[Jeeves] Doc types in DB:', [...new Set(types)]);
    }

    const select = document.getElementById('project-select');
    const currentValue = select.value;
    select.innerHTML = '<option value="">Select project...</option>';
    projects.forEach(p => {
      const opt = document.createElement('option');
      opt.value = p.projectId || p._id;
      opt.textContent = p.name;
      select.appendChild(opt);
    });
    // Restore selection
    if (currentValue) select.value = currentValue;
    // Update start button state
    const startBtn = document.getElementById('btn-start');
    if (startBtn && !timerStartTime && !isPaused) {
      startBtn.disabled = !select.value;
    }
  } catch (e) {
    console.error('[Jeeves] Failed to load projects:', e);
  }
}

// ─── Timer ──────────────────────────────────────────────────────────────────────

function setupEventListeners() {
  document.getElementById('btn-start').addEventListener('click', startTimer);
  document.getElementById('btn-pause').addEventListener('click', pauseTimer);
  document.getElementById('btn-stop').addEventListener('click', stopTimer);
  document.getElementById('btn-save-settings').addEventListener('click', saveSettings);
  document.getElementById('settings-toggle').addEventListener('click', () => {
    document.getElementById('settings-panel').classList.toggle('visible');
  });

  // Disable start button until a project is selected
  const projectSelect = document.getElementById('project-select');
  const startBtn = document.getElementById('btn-start');
  startBtn.disabled = true;
  projectSelect.addEventListener('change', () => {
    startBtn.disabled = !projectSelect.value;
  });
}

async function startTimer() {
  const projectId = document.getElementById('project-select').value;
  const task = document.getElementById('task-input').value.trim();

  if (!projectId) {
    // Don't start — button should be disabled, but just in case
    return;
  }

  if (isPaused) {
    // Resume from pause
    timerStartTime = Date.now();
    isPaused = false;
    startTickingDisplay();
    updateButtons('running');
    // Update the running entry doc
    await updateRunningEntry();
    return;
  }

  // Fresh start
  timerStartTime = Date.now();
  timerPausedElapsed = 0;
  isPaused = false;

  // Save running entry to PouchDB (syncs to CouchDB)
  const runningDoc = {
    _id: 'running_' + DEVICE_ID,
    type: 'running_entry',
    projectId: projectId,
    taskDescription: task || 'Untitled',
    startTime: timerStartTime,
    pausedElapsed: 0,
    deviceId: DEVICE_ID,
    updatedAt: Date.now()
  };

  try {
    // Remove existing running entry if any
    try {
      const existing = await localDb.get(runningDoc._id);
      await localDb.remove(existing);
    } catch (_) {}

    await localDb.put(runningDoc);
  } catch (e) {
    console.error('Failed to save running entry:', e);
  }

  startTickingDisplay();
  updateButtons('running');
}

function pauseTimer() {
  if (!timerStartTime) return;

  // Accumulate elapsed time
  timerPausedElapsed += Date.now() - timerStartTime;
  timerStartTime = null;
  isPaused = true;

  clearInterval(timerInterval);
  updateButtons('paused');
}

async function stopTimer() {
  if (!timerStartTime && !isPaused) return;

  // Calculate total duration
  let totalMs = timerPausedElapsed;
  if (timerStartTime) {
    totalMs += Date.now() - timerStartTime;
  }

  const projectId = document.getElementById('project-select').value;
  const task = document.getElementById('task-input').value.trim();

  // Create finalized time entry
  const entryId = generateId();
  const now = Date.now();
  const date = new Date(now).toISOString().slice(0, 10); // YYYY-MM-DD

  const entryDoc = {
    _id: 'entry_' + entryId,
    type: 'time_entry',
    entryId: entryId,
    projectId: projectId,
    taskDescription: task || 'Untitled',
    startTime: now - totalMs,
    endTime: now,
    durationMs: totalMs,
    date: date,
    isRunning: false,
    deviceId: DEVICE_ID,
    updatedAt: now
  };

  try {
    await localDb.put(entryDoc);

    // Remove running entry
    try {
      const running = await localDb.get('running_' + DEVICE_ID);
      await localDb.remove(running);
    } catch (_) {}
  } catch (e) {
    console.error('Failed to save entry:', e);
  }

  // Reset UI
  clearInterval(timerInterval);
  timerStartTime = null;
  timerPausedElapsed = 0;
  isPaused = false;
  document.getElementById('timer-display').textContent = '00:00:00';
  document.getElementById('timer-display').classList.remove('running');
  document.getElementById('task-input').value = '';
  updateButtons('idle');
  loadTodayEntries();
}

async function updateRunningEntry() {
  try {
    const doc = await localDb.get('running_' + DEVICE_ID);
    doc.pausedElapsed = timerPausedElapsed;
    doc.updatedAt = Date.now();
    await localDb.put(doc);
  } catch (_) {}
}

async function checkForRunningEntry() {
  try {
    const doc = await localDb.get('running_' + DEVICE_ID);
    if (doc && doc.type === 'running_entry') {
      // Restore timer state
      timerPausedElapsed = doc.pausedElapsed || 0;
      timerStartTime = Date.now(); // Resume from now (we lost exact pause time)
      document.getElementById('project-select').value = doc.projectId || '';
      document.getElementById('task-input').value = doc.taskDescription || '';
      startTickingDisplay();
      updateButtons('running');
    }
  } catch (_) {
    // No running entry — that's fine
  }
}

// ─── Display ────────────────────────────────────────────────────────────────────

function startTickingDisplay() {
  clearInterval(timerInterval);
  document.getElementById('timer-display').classList.add('running');
  timerInterval = setInterval(updateTimerDisplay, 1000);
  updateTimerDisplay();
}

function updateTimerDisplay() {
  let totalMs = timerPausedElapsed;
  if (timerStartTime) {
    totalMs += Date.now() - timerStartTime;
  }
  document.getElementById('timer-display').textContent = formatDuration(totalMs);
}

function updateButtons(state) {
  const start = document.getElementById('btn-start');
  const pause = document.getElementById('btn-pause');
  const stop = document.getElementById('btn-stop');
  const projectSelected = !!document.getElementById('project-select').value;

  switch (state) {
    case 'idle':
      start.disabled = !projectSelected; start.textContent = 'Start';
      pause.disabled = true;
      stop.disabled = true;
      break;
    case 'running':
      start.disabled = true;
      pause.disabled = false;
      stop.disabled = false;
      break;
    case 'paused':
      start.disabled = false; start.textContent = 'Resume';
      pause.disabled = true;
      stop.disabled = false;
      break;
  }
}

// ─── Today's Entries ────────────────────────────────────────────────────────────

async function loadTodayEntries() {
  const today = new Date().toISOString().slice(0, 10);
  try {
    const result = await localDb.allDocs({ include_docs: true });
    const entries = result.rows
      .map(r => r.doc)
      .filter(d => d.type === 'time_entry' && d.date === today)
      .sort((a, b) => (b.startTime || 0) - (a.startTime || 0));

    const container = document.getElementById('recent-entries');
    if (entries.length === 0) {
      container.innerHTML = '<p style="color: var(--text-dim); font-size: 0.85rem;">No entries today</p>';
      return;
    }

    container.innerHTML = entries.map(entry => {
      const projectName = getProjectName(entry.projectId);
      return `
        <div class="entry-item">
          <div class="left">
            <span class="project-name">${escapeHtml(projectName)}</span>
            <span class="task-desc">${escapeHtml(entry.taskDescription || 'Untitled')}</span>
          </div>
          <span class="duration">${formatDuration(entry.durationMs || 0)}</span>
        </div>
      `;
    }).join('');
  } catch (e) {
    console.error('Failed to load entries:', e);
  }
}

function getProjectName(projectId) {
  const select = document.getElementById('project-select');
  for (let i = 0; i < select.options.length; i++) {
    if (select.options[i].value === projectId) return select.options[i].textContent;
  }
  return projectId || 'Unknown';
}

// ─── Utilities ──────────────────────────────────────────────────────────────────

function formatDuration(ms) {
  const totalSeconds = Math.floor(ms / 1000);
  const h = Math.floor(totalSeconds / 3600);
  const m = Math.floor((totalSeconds % 3600) / 60);
  const s = totalSeconds % 60;
  return `${pad(h)}:${pad(m)}:${pad(s)}`;
}

function pad(n) { return n.toString().padStart(2, '0'); }

function generateId() {
  return Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
}

function escapeHtml(str) {
  const div = document.createElement('div');
  div.textContent = str;
  return div.innerHTML;
}
