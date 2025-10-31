const state = {
    users: [],
    currentUser: null,
    refreshHandle: null,
    scheduleHandle: null,
    depthHandle: null,
    sessionHandle: null,
    holdingsFilter: 'ALL',
    sessionActive: false
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
        selector.appendChild(option);
    });
    selector.addEventListener('change', () => {
        state.currentUser = selector.value;
        bootstrapUserSettings();
    });
    // Prefer selecting the active Kite session user if available
    try {
        const sess = await fetchJson('/api/admin/session/status');
        const preferred = sess?.user;
        if (preferred && users.some(u => u.username === preferred)) {
            selector.value = preferred;
            state.currentUser = preferred;
        } else if (users.length > 0 && !state.currentUser) {
            selector.selectedIndex = 0;
            state.currentUser = users[0].username;
        }
    } catch (_) {
        if (users.length > 0 && !state.currentUser) {
            selector.selectedIndex = 0;
            state.currentUser = users[0].username;
        }
    }
    bootstrapUserSettings();
}

async function bootstrapUserSettings() {
    const user = state.users.find(u => u.username === state.currentUser);
    if (!user) {
        return;
    }
    document.getElementById('logging-toggle').checked = !!user.loggingEnabled;
    document.getElementById('tradeDate').value = new Date().toISOString().split('T')[0];
    renderHoldings(user.holdings || []);
    await refreshSchedules();
    await refreshDepth();
    await fetchSessionStatus();
}

async function refreshSchedules() {
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
}

async function refreshDepth() {
    if (!state.currentUser) return;
    try {
        const depths = await fetchJson(`/api/depth/${state.currentUser}`);
        renderDepth(depths);
    } catch (err) {
        console.warn('Depth refresh failed', err);
        const table = document.getElementById('depth-table');
        if (table) {
            table.innerHTML = '<tr><td colspan="6" class="text-muted">Market depth unavailable. Click Refresh to retry.</td></tr>';
        }
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
        const priceChip = schedule.limitPrice != null && schedule.limitPrice !== ''
            ? `<span class=\"badge bg-secondary\">Limit: ${schedule.limitPrice}</span>`
            : '<span class=\"badge bg-secondary\">Market</span>';
        const auto = schedule.autoRepeat ? `<span class=\"badge bg-warning text-dark\">Auto Repeat</span>` : '';
        col.innerHTML = `
            <div class="card schedule-tile bg-dark border-light">
                <div class="card-body">
                    <div class="schedule-head">
                        <span class="badge bg-primary">${schedule.side}</span>
                    </div>
                    <div class="schedule-symbol">${schedule.tradingsymbol}</div>
                    <div class="schedule-meta">
                        <span class="badge bg-secondary">Qty: ${schedule.quantity}</span>
                        ${priceChip}
                    </div>
                    <div class="schedule-meta">
                        <span class="badge bg-secondary">Date: ${schedule.tradeDateIst}</span>
                        <span class="badge bg-secondary">Session: ${schedule.sessionTimeIst}</span>
                        ${auto}
                    </div>
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
        const auto = schedule.autoRepeat ? '<span class="badge bg-warning text-dark">Auto Repeat</span>' : '';
        const priceChip = schedule.limitPrice != null && schedule.limitPrice !== ''
            ? `<span class=\"badge bg-dark\">Limit: ${schedule.limitPrice}</span>`
            : '<span class=\"badge bg-dark\">Market</span>';
        col.innerHTML = `
            <div class="card bg-secondary schedule-tile">
                <div class="card-body">
                    <div class="schedule-head">
                        <span class="badge bg-primary">${schedule.side ?? '-'}</span>
                    </div>
                    <div class="schedule-symbol">${schedule.tradingsymbol}</div>
                    <div class="schedule-meta">
                        <span class="badge bg-dark">Qty: ${schedule.quantity ?? '-'}</span>
                        <span class="badge bg-dark">Session: ${schedule.sessionTimeIst}</span>
                        <span class="badge ${badgeClass}">${schedule.status}</span>
                        ${priceChip}
                        ${auto}
                    </div>
                    <p class="card-text small-note">Last Exec: ${schedule.lastExecutedAt ?? 'N/A'}</p>
                    <p class="card-text small-note">Message: ${schedule.lastExecutionMessage ?? 'N/A'}</p>
                    <button class="btn btn-sm btn-outline-light" data-action="repeat" data-id="${schedule.id}">Repeat Tomorrow</button>
                </div>
            </div>`;
        container.appendChild(col);
    });
    container.querySelectorAll('button').forEach(btn => btn.addEventListener('click', handleScheduleAction));
}

function parseHolding(entry) {
    if (!entry) return { exchange: null, symbol: '' };
    // Format: EXCH:SYMBOL|QTY|AVG|LAST|PNL|PCT|PRODUCT|TOKEN (tail segments optional)
    let main = entry;
    let qty = null, avg = null, last = null, pnl = null, pct = null, product = null, token = null;
    const parts = entry.split('|');
    if (parts.length > 1) {
        main = parts[0];
        qty = parts[1] ? Number(parts[1]) : null;
        avg = parts[2] ? Number(parts[2]) : null;
        last = parts[3] ? Number(parts[3]) : null;
        pnl = parts[4] ? Number(parts[4]) : null;
        pct = parts[5] ? Number(parts[5]) : null;
        product = parts[6] || null;
        token = parts[7] || null;
    }
    const idx = main.indexOf(':');
    const exchange = idx > 0 ? main.substring(0, idx) : null;
    const symbol = idx > 0 ? main.substring(idx + 1) : main;
    return { exchange, symbol, qty, avg, last, pnl, pct, product, token };
}

function renderHoldings(holdings) {
    const container = document.getElementById('holdings-list');
    container.innerHTML = '';
    if (!holdings || holdings.length === 0) {
        container.innerHTML = '<span class="text-muted">No holdings configured.</span>';
        return;
    }
    const filter = state.holdingsFilter;
    holdings.forEach(entry => {
        const { exchange, symbol, qty, avg, last, pnl, pct, product, token } = parseHolding(entry);
        if (filter !== 'ALL' && exchange && exchange.toUpperCase() !== filter) return;
        const chip = document.createElement('div');
        chip.className = 'holding-chip';
        chip.title = [
            exchange ? `Exchange: ${exchange}` : null,
            qty != null ? `Qty: ${qty}` : null,
            avg != null ? `Avg: ${avg.toFixed(2)}` : null,
            last != null ? `LTP: ${last.toFixed(2)}` : null,
            pnl != null ? `PnL: ${pnl.toFixed(2)}` : null,
            pct != null ? `Day%: ${pct.toFixed(2)}` : null,
            product ? `Product: ${product}` : null,
            token ? `Token: ${token}` : null
        ].filter(Boolean).join(' | ');
        chip.innerHTML = `
            <div class="chip-line"><strong>${symbol}</strong>${exchange ? ` <span class="text-muted">(${exchange})</span>` : ''}</div>
            <div class="chip-meta text-muted">${
                [
                    qty != null ? `Qty ${qty}` : null,
                    avg != null ? `Avg ${avg.toFixed(2)}` : null,
                    last != null ? `LTP ${last.toFixed(2)}` : null,
                    pct != null ? `${pct.toFixed(2)}%` : null
                ].filter(Boolean).join(' · ')
            }</div>`;
        chip.addEventListener('click', () => presetSchedule({ symbol, token, qty }));
        container.appendChild(chip);
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

function presetSchedule(holding) {
    const symbol = typeof holding === 'string' ? holding : holding.symbol;
    const token = typeof holding === 'string' ? null : holding.token;
    const qty = typeof holding === 'string' ? null : holding.qty;
    document.getElementById('tradingsymbol').value = symbol;
    if (token) document.getElementById('instrumentToken').value = token;
    if (qty != null && !Number.isNaN(qty)) document.getElementById('quantity').value = qty;
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
    refreshSchedules();
}

async function fetchSessionStatus() {
    try {
        const status = await fetchJson('/api/admin/session/status');
        const badge = document.getElementById('session-status');
        const etaEl = document.getElementById('session-eta');
        const expiryMs = status.expiry ? Date.parse(status.expiry) : null;
        const isExpired = !!(status.active && expiryMs && expiryMs <= Date.now());
        if (status.active && !isExpired) {
            state.sessionActive = true;
            badge.className = 'badge bg-success';
            const userLabel = status.user ? `as ${status.user}` : '';
            badge.innerHTML = `<i class="bi bi-check-circle me-1"></i>Active → Expires ${status.expiry ?? ''}`;
            if (etaEl) {
                const ms = expiryMs - Date.now();
                const m = Math.max(0, Math.round(ms / 60000));
                etaEl.textContent = `(expires in ${m}m)`;
            }
        } else if (isExpired) {
            state.sessionActive = false;
            badge.className = 'badge bg-warning text-dark';
            const userLabel = status.user ? `as ${status.user}` : '';
            badge.innerHTML = `<i class="bi bi-exclamation-triangle me-1"></i>Session expired ${userLabel}`;
            if (etaEl) etaEl.textContent = '';
        } else {
            state.sessionActive = false;
            badge.className = 'badge bg-secondary';
            badge.innerHTML = `<i class="bi bi-slash-circle me-1"></i>No active session`;
            if (etaEl) etaEl.textContent = '';
        }
        // Toggle Login/Logout/Reconnect UI
        const connectBtn = document.getElementById('kite-connect-btn');
        const tokenInput = document.getElementById('kite-request-token');
        const logoutBtn = document.getElementById('kite-logout-btn');
        if (connectBtn && tokenInput && logoutBtn) {
            if (!status.active) {
                connectBtn.dataset.mode = 'login';
                connectBtn.textContent = 'Login';
                if (window.setTooltip) setTooltip(connectBtn,'Login');
                connectBtn.disabled = false;
                connectBtn.classList.remove('btn-outline-secondary');
                connectBtn.classList.add('btn-primary');
                tokenInput.style.display = '';
                logoutBtn.disabled = true;
                logoutBtn.classList.add('disabled');
                if (window.setTooltip) setTooltip(logoutBtn,'Logout');
            } else if (isExpired) {
                connectBtn.dataset.mode = 'login';
                connectBtn.textContent = 'Reconnect';
                if (window.setTooltip) setTooltip(connectBtn,'Reconnect');
                connectBtn.disabled = false;
                connectBtn.classList.remove('btn-outline-secondary');
                connectBtn.classList.add('btn-primary');
                tokenInput.style.display = '';
                logoutBtn.disabled = false;
                logoutBtn.classList.remove('disabled');
                if (window.setTooltip) setTooltip(logoutBtn,'Logout');
            } else {
                connectBtn.dataset.mode = 'noop';
                connectBtn.textContent = 'Reconnect';
                if (window.setTooltip) setTooltip(connectBtn,'Reconnect');
                connectBtn.disabled = true;
                connectBtn.classList.remove('btn-primary');
                connectBtn.classList.add('btn-outline-secondary');
                tokenInput.style.display = 'none';
                logoutBtn.disabled = false;
                logoutBtn.classList.remove('disabled');
                if (window.setTooltip) setTooltip(logoutBtn,'Logout');
            }
        }
    } catch (err) {
        document.getElementById('session-status').textContent = 'Session status unavailable';
        const eta = document.getElementById('session-eta'); if (eta) eta.textContent = '';
    }
}

function scheduleAutoRefresh() {
    if (state.refreshHandle) clearInterval(state.refreshHandle);
    if (state.scheduleHandle) clearInterval(state.scheduleHandle);
    if (state.depthHandle) clearInterval(state.depthHandle);
    if (state.sessionHandle) clearInterval(state.sessionHandle);
    // Schedules: every 15s, Depth: every 8s, Session status: every 60s
    state.scheduleHandle = setInterval(refreshSchedules, 15000);
    state.depthHandle = setInterval(refreshDepth, 8000);
    state.sessionHandle = setInterval(fetchSessionStatus, 60000);
}

function setTooltip(el, title) {
    if (!el) return;
    el.setAttribute("title", title);
    try {
        if (window.bootstrap && bootstrap.Tooltip) {
            const inst = bootstrap.Tooltip.getInstance(el);
            if (inst) inst.dispose();
            new bootstrap.Tooltip(el);
        }
    } catch (_) {}
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
            refreshSchedules();
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

    document.getElementById('refresh-btn').addEventListener('click', async () => {
        await refreshSchedules();
        await refreshDepth();
        await fetchSessionStatus();
    });

    // Kite connect handlers if present in DOM
    const connectBtn = document.getElementById('kite-connect-btn');
    const logoutBtn = document.getElementById('kite-logout-btn');
    const syncBtn = document.getElementById('sync-holdings-btn');
    const holdingsFilter = document.getElementById('holdings-exchange-filter');
    if (connectBtn) {
        connectBtn.addEventListener('click', async () => {
            if (connectBtn.dataset.mode === 'logout') {
                try {
                    await fetchJson('/api/admin/session/logout', { method: 'POST' });
                    await fetchSessionStatus();
                } catch (e) {
                    alert(e.message || 'Logout failed');
                }
                return;
            }
            const input = document.getElementById('kite-request-token');
            const token = (input?.value || '').trim();
            if (!token) {
                alert('Enter the Kite request_token');
                return;
            }
            try {
                const resp = await fetchJson('/api/admin/session/login', {
                    method: 'POST',
                    body: JSON.stringify({ requestToken: token })
                });
                await fetchSessionStatus();
                await loadUsers();
                if (resp?.user) {
                    const selector = document.getElementById('user-selector');
                    selector.value = resp.user;
                    state.currentUser = resp.user;
                    bootstrapUserSettings();
                }
                alert(`Kite connected${resp.user ? ' as ' + resp.user : ''}`);
            } catch (e) {
                alert(e.message || 'Kite login failed');
            }
        });
    }
    if (logoutBtn) {
        logoutBtn.addEventListener('click', async () => {
            try {
                await fetchJson('/api/admin/session/logout', { method: 'POST' });
                await fetchSessionStatus();
            } catch (e) {
                alert(e.message || 'Logout failed');
            }
        });
    }
    if (syncBtn) {
        syncBtn.addEventListener('click', async () => {
            try {
                const res = await fetchJson('/api/admin/session/holdings/sync', { method: 'POST' });
                await loadUsers();
                // Prefer showing the active session user after sync
                try {
                    const sess = await fetchJson('/api/admin/session/status');
                    if (sess?.user) {
                        const selector = document.getElementById('user-selector');
                        selector.value = sess.user;
                        state.currentUser = sess.user;
                        bootstrapUserSettings();
                    }
                } catch(_) {}
                alert(`Holdings synced (${res.updated ?? 0})`);
            } catch (e) {
                alert(e.message || 'Sync failed');
            }
        });
    }
    if (holdingsFilter) {
        holdingsFilter.addEventListener('change', () => {
            state.holdingsFilter = holdingsFilter.value;
            const user = state.users.find(u => u.username === state.currentUser);
            renderHoldings(user?.holdings || []);
        });
    }
}

async function init() {
    await loadUsers();
    setupFormHandlers();
    // Initial fetches
    await refreshSchedules();
    await refreshDepth();
    await fetchSessionStatus();
    scheduleAutoRefresh();
    // Enable Bootstrap tooltips, if available
    try {
        if (window.bootstrap && bootstrap.Tooltip) {
            document.querySelectorAll('[data-bs-toggle="tooltip"]').forEach(el => new bootstrap.Tooltip(el));
        }
    } catch (_) {}
}

window.addEventListener('DOMContentLoaded', init);

















