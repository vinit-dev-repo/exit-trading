const state = {
    users: [],
    currentUser: null,
    refreshHandle: null
};

const STATUS = {
    SCHEDULED: 'SCHEDULED',
    EXECUTED: 'EXECUTED',
    FAILED: 'FAILED'
};

async function fetchJson(url, options = {}) {
    const response = await fetch(url, {
        headers: {
            'Content-Type': 'application/json'
        },
        credentials: 'same-origin',
        ...options
    });
    if (!response.ok) {
        const message = await response.text();
        throw new Error(message || 'Request failed');
    }
    return response.json();
}

function formatPressure(buy, sell) {
    if (buy === 0 && sell === 0) return 'Neutral';
    const total = buy + sell;
    const buyPct = Math.round((buy / total) * 100);
    const sellPct = 100 - buyPct;
    return `Buy ${buyPct}% / Sell ${sellPct}%`;
}

async function loadUsers() {
    const users = await fetchJson('/api/admin/users');
    state.users = users;
    const selector = document.getElementById('user-selector');
    selector.innerHTML = '';
    users.forEach((user, idx) => {
        const option = document.createElement('option');
        option.value = user.username;
        option.textContent = `${user.displayName} (${user.username})`;
        if (idx === 0) {
            option.selected = true;
            state.currentUser = user.username;
        }
        selector.appendChild(option);
    });
    selector.addEventListener('change', () => {
        state.currentUser = selector.value;
        bootstrapUserSettings();
    });
    bootstrapUserSettings();
}

async function bootstrapUserSettings() {
    const user = state.users.find(u => u.username === state.currentUser);
    document.getElementById('logging-toggle').checked = !!user.loggingEnabled;
    document.getElementById('tradeDate').value = new Date().toISOString().split('T')[0];
    renderHoldings(user.holdings || []);
    refreshData();
}

async function refreshData() {
    if (!state.currentUser) return;
    try {
        const data = await fetchJson(`/api/schedules/${state.currentUser}`);
        const scheduled = data.filter(s => s.status === STATUS.SCHEDULED);
        const executed = data.filter(s => s.status === STATUS.EXECUTED || s.status === STATUS.FAILED);
        renderSchedules(scheduled);
        renderExecuted(executed);
    } catch (err) {
        console.error(err);
    }
    refreshDepth();
    fetchSessionStatus();
}

async function refreshDepth() {
    if (!state.currentUser) return;
    try {
        const depths = await fetchJson(`/api/depth/${state.currentUser}`);
        renderDepth(depths);
    } catch (err) {
        console.warn('Depth refresh failed', err);
    }
}

function renderSchedules(schedules) {
    const container = document.getElementById('scheduled-list');
    container.innerHTML = '';
    if (schedules.length === 0) {
        container.innerHTML = '<p class="text-muted">No upcoming schedules.</p>';
        return;
    }
    schedules.forEach(schedule => {
        const col = document.createElement('div');
        col.className = 'col-md-6 col-xl-4';
        col.innerHTML = `
            <div class="card schedule-tile bg-dark border-light">
                <div class="card-body">
                    <h5 class="card-title">${schedule.tradingsymbol} <span class="badge bg-primary">${schedule.side}</span></h5>
                    <p class="card-text mb-1">Qty: ${schedule.quantity} | Session: ${schedule.sessionTimeIst}</p>
                    <p class="card-text mb-1">Trade Date: ${schedule.tradeDateIst}</p>
                    <p class="card-text text-muted">Next Execution: ${schedule.nextExecutionTime ?? 'Pending'}</p>
                    <div class="d-flex gap-2">
                        <button class="btn btn-sm btn-outline-danger" data-action="cancel" data-id="${schedule.id}">Cancel</button>
                        <button class="btn btn-sm btn-outline-warning" data-action="repeat" data-id="${schedule.id}">Repeat Tomorrow</button>
                    </div>
                </div>
            </div>`;
        container.appendChild(col);
    });
    container.querySelectorAll('button').forEach(btn => {
        btn.addEventListener('click', handleScheduleAction);
    });
}

function renderExecuted(executed) {
    const container = document.getElementById('executed-list');
    container.innerHTML = '';
    if (executed.length === 0) {
        container.innerHTML = '<p class="text-muted">No executed schedules yet.</p>';
        return;
    }
    executed.forEach(schedule => {
        const badgeClass = schedule.status === STATUS.EXECUTED ? 'bg-success' : 'bg-danger';
        const col = document.createElement('div');
        col.className = 'col-md-6 col-xl-4';
        const nextDayFlag = schedule.autoRepeat ? '<span class="badge bg-warning text-dark ms-2">Auto Repeat</span>' : '';
        col.innerHTML = `
            <div class="card bg-secondary schedule-tile">
                <div class="card-body">
                    <h5 class="card-title">${schedule.tradingsymbol} <span class="badge ${badgeClass}">${schedule.status}</span>${nextDayFlag}</h5>
                    <p class="card-text mb-1">Session: ${schedule.sessionTimeIst}</p>
                    <p class="card-text mb-1">Message: ${schedule.lastExecutionMessage ?? 'N/A'}</p>
                    <p class="card-text text-muted">Last Exec: ${schedule.lastExecutedAt ?? 'N/A'}</p>
                    <button class="btn btn-sm btn-outline-light" data-action="repeat" data-id="${schedule.id}">Repeat Tomorrow</button>
                </div>
            </div>`;
        container.appendChild(col);
    });
    container.querySelectorAll('button').forEach(btn => btn.addEventListener('click', handleScheduleAction));
}

function renderHoldings(holdings) {
    const container = document.getElementById('holdings-list');
    container.innerHTML = '';
    if (!holdings || holdings.length === 0) {
        container.innerHTML = '<span class="text-muted">No holdings configured.</span>';
        return;
    }
    holdings.forEach(symbol => {
        const badge = document.createElement('span');
        badge.className = 'badge rounded-pill bg-light text-dark p-3';
        badge.style.cursor = 'pointer';
        badge.textContent = symbol;
        badge.addEventListener('click', () => presetSchedule(symbol));
        container.appendChild(badge);
    });
}

function renderDepth(depths) {
    const table = document.getElementById('depth-table');
    table.innerHTML = '';
    if (!depths || depths.length === 0) {
        table.innerHTML = '<tr><td colspan="6" class="text-muted">No data</td></tr>';
        return;
    }
    depths.forEach(entry => {
        const pressure = formatPressure(entry.buyQuantity || 0, entry.sellQuantity || 0);
        const row = document.createElement('tr');
        row.innerHTML = `
            <td>${entry.tradingsymbol}</td>
            <td>${entry.buyQuantity ?? 0}</td>
            <td>${entry.sellQuantity ?? 0}</td>
            <td>${pressure}</td>
            <td>${entry.ltp ?? '-'}</td>
            <td>${entry.capturedAt ?? '-'}</td>`;
        table.appendChild(row);
    });
}

function presetSchedule(symbol) {
    document.getElementById('tradingsymbol').value = symbol;
    document.getElementById('instrumentToken').focus();
}

async function handleScheduleAction(event) {
    const id = event.target.dataset.id;
    const action = event.target.dataset.action;
    if (action === 'cancel') {
        await fetch(`/api/schedules/${id}`, { method: 'DELETE' });
    }
    if (action === 'repeat') {
        await fetch(`/api/schedules/${id}/repeat`, { method: 'POST' });
    }
    refreshData();
}

async function fetchSessionStatus() {
    try {
        const status = await fetchJson('/api/admin/session/status');
        const badge = document.getElementById('session-status');
        if (status.active) {
            badge.className = 'badge bg-success';
            const userLabel = status.user ? ` · User ${status.user}` : '';
            badge.textContent = `Session Active${userLabel} · Expiry ${status.expiry ?? ''}`;
        } else {
            badge.className = 'badge bg-danger';
            badge.textContent = 'Session inactive';
        }
    } catch (err) {
        document.getElementById('session-status').textContent = 'Session status unavailable';
    }
}

function scheduleAutoRefresh() {
    if (state.refreshHandle) clearInterval(state.refreshHandle);
    state.refreshHandle = setInterval(refreshData, 5000);
}

function setupFormHandlers() {
    const form = document.getElementById('schedule-form');
    form.addEventListener('submit', async (event) => {
        event.preventDefault();
        if (!form.checkValidity()) {
            form.classList.add('was-validated');
            return;
        }
        const payload = {
            tradingsymbol: document.getElementById('tradingsymbol').value.trim().toUpperCase(),
            instrumentToken: document.getElementById('instrumentToken').value.trim(),
            quantity: Number(document.getElementById('quantity').value),
            side: document.getElementById('side').value,
            sessionSlot: document.getElementById('sessionSlot').value,
            tradeDate: document.getElementById('tradeDate').value,
            limitPrice: document.getElementById('limitPrice').value ? Number(document.getElementById('limitPrice').value) : null,
            autoRepeat: document.getElementById('autoRepeat').checked,
            cancelOpenOrdersBeforeExecution: document.getElementById('cancelOrders').checked
        };
        try {
            await fetchJson(`/api/schedules/${state.currentUser}`, {
                method: 'POST',
                body: JSON.stringify(payload)
            });
            form.reset();
            document.getElementById('tradeDate').value = new Date().toISOString().split('T')[0];
            refreshData();
        } catch (err) {
            alert(err.message || 'Failed to schedule');
        }
    });

    document.getElementById('logging-toggle').addEventListener('change', async (event) => {
        await fetch(`/api/admin/logging/${state.currentUser}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ enabled: event.target.checked })
        });
    });

    document.getElementById('refresh-btn').addEventListener('click', refreshData);
}

async function init() {
    await loadUsers();
    setupFormHandlers();
    refreshData();
    scheduleAutoRefresh();
}

window.addEventListener('DOMContentLoaded', init);
