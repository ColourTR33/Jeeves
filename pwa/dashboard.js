/**
 * Jeeves Dashboard — Public weekly burn view.
 *
 * Reads time entries and project data from CouchDB (via PouchDB).
 * Shows per-project hours burned vs allocated for the current week.
 * Colour-coded: green (time remaining), blue (fully burned), red (over allocation).
 *
 * Publicly accessible — no auth needed if CouchDB allows public reads
 * or credentials are embedded in the URL.
 */

let db = null;

// ─── Init ───────────────────────────────────────────────────────────────────────

document.addEventListener('DOMContentLoaded', () => {
  initDashboard();
});

function initDashboard() {
  const url = localStorage.getItem('couchDbUrl');
  const user = localStorage.getItem('couchDbUser');
  const pass = localStorage.getItem('couchDbPass');

  if (!url) {
    document.getElementById('dashboard-grid').innerHTML =
      '<p class="no-data">No CouchDB configured. Open the <a href="/" style="color: var(--blue)">timer app</a> and configure settings first.</p>';
    return;
  }

  const remoteUrl = user && pass
    ? url.replace('://', `://${encodeURIComponent(user)}:${encodeURIComponent(pass)}@`)
    : url;

  db = new PouchDB(remoteUrl);
  loadDashboard();

  // Refresh every 2 hours (7,200,000 ms)
  setInterval(loadDashboard, 2 * 60 * 60 * 1000);
}

// ─── Data Loading ───────────────────────────────────────────────────────────────

async function loadDashboard() {
  try {
    const result = await db.allDocs({ include_docs: true });
    const docs = result.rows.map(r => r.doc);

    const projects = docs.filter(d => d.type === 'project');
    const entries = docs.filter(d => d.type === 'time_entry' && !d.isRunning);

    // Get current week bounds (Monday-Sunday)
    const { monday, sunday } = getCurrentWeekBounds();
    const weekEntries = entries.filter(e => e.date >= monday && e.date <= sunday);

    // Find the weekly plan for allocation targets
    const weeklyPlan = docs.find(d => d.type === 'weekly_plan' && d.weekStartDate === monday);
    const planTargets = {};
    if (weeklyPlan && weeklyPlan.targets) {
      weeklyPlan.targets.forEach(t => { planTargets[t.projectId] = t.targetHours; });
    }

    // Group hours by project
    const hoursByProject = {};
    weekEntries.forEach(entry => {
      const ms = entry.durationMs || 0;
      hoursByProject[entry.projectId] = (hoursByProject[entry.projectId] || 0) + ms / 3_600_000;
    });

    // Build dashboard cards
    const grid = document.getElementById('dashboard-grid');
    const cards = [];

    projects
      .filter(p => !p.isDistributed)
      .sort((a, b) => (a.name || '').localeCompare(b.name || ''))
      .forEach(project => {
        const projectId = project.projectId || project._id.replace('project_', '');
        const burned = hoursByProject[projectId] || 0;
        // Use weekly plan allocation if available, else fall back to project default
        const allocated = planTargets[projectId] || project.defaultTargetHours || 0;

        // Skip projects with no allocation and no work this week
        if (allocated === 0 && burned === 0) return;

        const percentage = allocated > 0 ? (burned / allocated) * 100 : (burned > 0 ? 100 : 0);
        const displayPercent = Math.min(percentage, 100); // Cap bar at 100%

        let status, barClass;
        if (allocated > 0 && percentage >= 95 && percentage <= 105) {
          status = 'complete'; barClass = 'blue';
        } else if (percentage > 105) {
          status = 'over'; barClass = 'red';
        } else {
          status = 'under'; barClass = 'green';
        }

        cards.push(`
          <div class="project-card ${status}">
            <div class="project-name">${escapeHtml(project.name)}</div>
            <div class="bar-container">
              <div class="bar-fill ${barClass}" style="width: ${displayPercent}%"></div>
              <span class="bar-label">${Math.round(percentage)}%</span>
            </div>
            <div class="stats">
              <span>${burned.toFixed(1)}h burned</span>
              <span>${allocated > 0 ? allocated.toFixed(1) + 'h allocated' : 'No allocation'}</span>
            </div>
          </div>
        `);
      });

    if (cards.length === 0) {
      grid.innerHTML = '<p class="no-data">No project data for this week yet.</p>';
    } else {
      grid.innerHTML = cards.join('');
    }

    // Update labels
    document.getElementById('week-label').textContent = `Week: ${monday} → ${sunday}`;
    document.getElementById('updated-at').textContent = `Last updated: ${new Date().toLocaleTimeString()}`;

  } catch (e) {
    document.getElementById('dashboard-grid').innerHTML =
      `<p class="no-data">Failed to load data: ${e.message}</p>`;
  }
}

// ─── Helpers ────────────────────────────────────────────────────────────────────

function getCurrentWeekBounds() {
  const now = new Date();
  const day = now.getDay(); // 0=Sun, 1=Mon, ...
  const diffToMonday = day === 0 ? -6 : 1 - day;

  const monday = new Date(now);
  monday.setDate(now.getDate() + diffToMonday);

  const sunday = new Date(monday);
  sunday.setDate(monday.getDate() + 6);

  return {
    monday: formatDate(monday),
    sunday: formatDate(sunday)
  };
}

function formatDate(d) {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

function escapeHtml(str) {
  const div = document.createElement('div');
  div.textContent = str || '';
  return div.innerHTML;
}
