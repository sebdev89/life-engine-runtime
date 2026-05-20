/**
 * Runtime Cockpit — minimal ops UI (no auth).
 * Served from life-engine-runtime static resources.
 */

const API = '';

const $ = (id) => document.getElementById(id);

const state = {
  runId: null,
  es: null,
  pollTimer: null,
  seenEventIds: new Set(),
};

function setLive(mode) {
  const badge = $('live-badge');
  badge.className = 'badge';
  if (mode === 'live') {
    badge.classList.add('badge--live');
    badge.textContent = 'LIVE';
  } else if (mode === 'err') {
    badge.classList.add('badge--err');
    badge.textContent = 'DISCONNECTED';
  } else {
    badge.classList.add('badge--off');
    badge.textContent = 'OFFLINE';
  }
}

function showError(msg) {
  const el = $('error-banner');
  if (!msg) {
    el.hidden = true;
    el.textContent = '';
    return;
  }
  el.hidden = false;
  el.textContent = msg;
}

function setActionStatus(msg) {
  $('action-status').textContent = msg ?? '';
}

function formatTime(iso) {
  if (!iso) return '—';
  try {
    return new Date(iso).toLocaleTimeString(undefined, {
      hour12: false,
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      fractionalSecondDigits: 3,
    });
  } catch {
    return iso;
  }
}

function renderRun(run) {
  if (!run) return;
  $('run-id').textContent = run.runId ?? '—';
  $('run-status').textContent = run.status ?? '—';
  $('run-workflow').textContent = run.workflowId ?? '—';
  $('run-correlation').textContent = run.correlationId ?? '—';
  $('run-updated').textContent = formatTime(run.updatedAt);
  const terminal = ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(run.status);
  $('btn-cancel').disabled = !run.runId || terminal;
  $('btn-reconnect').disabled = !run.runId;
}

function appendEvent(ev) {
  if (!ev?.eventId || state.seenEventIds.has(ev.eventId)) return;
  state.seenEventIds.add(ev.eventId);

  const tbody = $('events-body');
  if (tbody.querySelector('.muted')) {
    tbody.innerHTML = '';
  }

  const tr = document.createElement('tr');
  if (ev.terminal) tr.classList.add('event--terminal');

  const payload = {
    eventId: ev.eventId,
    runId: ev.runId,
    attributes: ev.attributes ?? {},
    terminal: ev.terminal,
  };

  tr.innerHTML = `
    <td>${formatTime(ev.occurredAt)}</td>
    <td><code>${escapeHtml(ev.type ?? '')}</code></td>
    <td>${escapeHtml(ev.source ?? '')}</td>
    <td class="payload">${escapeHtml(JSON.stringify(payload, null, 0))}</td>
  `;
  tbody.appendChild(tr);
  tr.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
}

function escapeHtml(s) {
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');
}

async function fetchRun(runId) {
  const res = await fetch(`${API}/api/runtime/runs/${runId}`);
  if (!res.ok) throw new Error(`GET run failed: ${res.status}`);
  return res.json();
}

async function refreshRun() {
  if (!state.runId) return;
  try {
    const run = await fetchRun(state.runId);
    renderRun(run);
  } catch (e) {
    showError(e.message ?? String(e));
  }
}

function stopPoll() {
  if (state.pollTimer) {
    clearInterval(state.pollTimer);
    state.pollTimer = null;
  }
}

function startPoll() {
  stopPoll();
  state.pollTimer = setInterval(refreshRun, 1500);
}

function closeSse() {
  if (state.es) {
    state.es.close();
    state.es = null;
  }
}

function connectSse(runId) {
  closeSse();
  showError('');
  setLive('off');

  const url = `${API}/api/runtime/runs/${runId}/events`;
  const es = new EventSource(url);
  state.es = es;

  es.onopen = () => {
    setLive('live');
    showError('');
    setActionStatus('SSE connected');
  };

  const handleSseMessage = (msg) => {
    if (!msg.data || msg.data.startsWith(':')) return;
    try {
      const ev = JSON.parse(msg.data);
      appendEvent(ev);
      if (ev.terminal) {
        closeSse();
        setLive('off');
        setActionStatus('Stream ended (terminal event)');
        refreshRun();
        stopPoll();
      }
    } catch (e) {
      console.warn('SSE parse error', e, msg.data);
      showError(`SSE parse error: ${e.message ?? e}`);
    }
  };

  es.onmessage = handleSseMessage;

  const namedTypes = [
    'RUN_STARTED',
    'AGENT_STARTED',
    'AGENT_COMPLETED',
    'RUN_COMPLETED',
    'RUN_CANCELLED',
    'RUN_FAILED',
  ];
  for (const t of namedTypes) {
    es.addEventListener(t, handleSseMessage);
  }

  es.onerror = () => {
    if (es.readyState === EventSource.CLOSED) {
      setLive('err');
      showError(
        'SSE connection closed. Use Reconnect SSE or start a new run if the workflow already finished.',
      );
      setActionStatus('');
      closeSse();
    } else {
      setLive('err');
      showError('SSE error — connection lost or server unavailable.');
    }
  };
}

async function startDemoRun() {
  $('btn-start').disabled = true;
  setActionStatus('Starting…');
  showError('');
  closeSse();
  stopPoll();
  state.runId = null;
  state.seenEventIds.clear();
  $('events-body').innerHTML =
    '<tr><td colspan="4" class="muted">Waiting for events…</td></tr>';

  try {
    const res = await fetch(`${API}/api/runtime/runs`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        workflowId: 'demo.llm.workflow',
        input: 'Life Engine runtime observability check from static cockpit.',
        correlationId: `cockpit-${Date.now()}`,
      }),
    });
    if (!res.ok) throw new Error(`POST run failed: ${res.status}`);
    const run = await res.json();
    state.runId = run.runId;
    renderRun(run);
    connectSse(run.runId);
    startPoll();
    setActionStatus(`Run ${run.runId} started`);
  } catch (e) {
    showError(e.message ?? String(e));
    setLive('err');
  } finally {
    $('btn-start').disabled = false;
  }
}

async function cancelRun() {
  if (!state.runId) return;
  $('btn-cancel').disabled = true;
  try {
    const res = await fetch(`${API}/api/runtime/runs/${state.runId}/cancel`, {
      method: 'POST',
    });
    if (!res.ok) {
      const body = await res.text();
      throw new Error(`Cancel failed: ${res.status} ${body}`);
    }
    const run = await res.json();
    renderRun(run);
    setActionStatus('Run cancelled');
    closeSse();
    setLive('off');
    stopPoll();
    await refreshRun();
  } catch (e) {
    showError(e.message ?? String(e));
  } finally {
    $('btn-cancel').disabled = false;
  }
}

function reconnectSse() {
  if (!state.runId) return;
  state.seenEventIds.clear();
  $('events-body').innerHTML =
    '<tr><td colspan="4" class="muted">Waiting for events…</td></tr>';
  connectSse(state.runId);
  startPoll();
}

$('btn-start').addEventListener('click', startDemoRun);
$('btn-cancel').addEventListener('click', cancelRun);
$('btn-reconnect').addEventListener('click', reconnectSse);
