const state = {
    users: [],
    currentUser: null,
    refreshHandle: null,
    scheduleHandle: null,
    depthHandle: null,
    sessionHandle: null,
    holdingsFilter: 'ALL',
    sessionActive: false,
    selectedSymbol: null,
    depthPage: 0,
    depthPageSize: 2,
    depthCards: [],
    analysisBySymbol: new Map()
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
    await refreshAuctionSummary();
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
    let depths;
    // Fetch first; show unavailable only on fetch failure
    try {
        const uname = encodeURIComponent(state.currentUser || '');
        depths = await fetchJson(`/api/depth/${uname}`);
    } catch (err) {
        console.warn('Depth fetch failed', err);
        const table = document.getElementById('depth-table');
        if (table) {
            table.innerHTML = '<tr><td colspan="6" class="text-muted">Market depth unavailable. Click Retry to try again.</td></tr>';
        }
        const panels = document.getElementById('depth-panels');
        if (panels) panels.innerHTML = '<div class="et-depth-muted">Market depth unavailable. Click Retry to try again.</div>';
        const retryBtn = document.getElementById('retry-depth');
        if (retryBtn) retryBtn.disabled = false;
        return;
    }
    // Render; keep render errors non-fatal
    try {
        renderDepth(depths);
    } catch (err) {
        console.warn('Depth render failed', err);
        const panels = document.getElementById('depth-panels');
        if (panels && !panels.innerHTML) {
            panels.innerHTML = '<div class="et-depth-muted">Unable to render depth. Click Retry.</div>';
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
                    <button class="btn btn-sm btn-outline-warning" data-action="repeat" data-id="${schedule.id}">Repeat Tomorrow</button>
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
            pnl != null ? `P&L: ${pnl.toFixed(2)}` : null,
            pct != null ? `Day%: ${pct.toFixed(2)}` : null,
            product ? `Product: ${product}` : null,
            token ? `Token: ${token}` : null
        ].filter(Boolean).join(' | ');
        chip.innerHTML = `
            <div class="chip-line"><strong>${symbol}</strong>${exchange ? ` <span class="text-muted">(${exchange})</span>` : ''} </strong>${product ? ` <span class="text-muted">${product}</span>` : ''} </div>
            <div class="chip-meta text-muted">${
                [
                    qty != null ? `Qty: ${qty}` : null,
                    avg != null ? `Avg: ${avg.toFixed(2)}` : null,
                    last != null ? `LTP: ${last.toFixed(2)}` : null,
                    pct != null ? `Day(%): ${pct.toFixed(2)}` : null,
                    pnl != null ? `P&L: ${Number(pnl).toFixed(2)}` : null,
                ].filter(Boolean).join(' · ')
            }</div>`;
        // Append final flag values (analysis) inside holding chip
        try {
            const summary = state.analysisBySymbol.get(symbol);
            if (summary) {
                const flags = document.createElement('div');
                flags.className = 'chip-flags';
                const parts = [];
                if (typeof summary.obi === 'number') {
                    const obiPct = Math.round(summary.obi * 100);
                    parts.push(`<span class="chip-flag">OBI ${obiPct > 0 ? '+' : ''}${obiPct}%</span>`);
                }
                if (summary.swing != null) {
                    parts.push(`<span class="chip-flag">Swing ${Number(summary.swing).toFixed(2)}</span>`);
                }
                if (summary.sellSpikeScore != null) {
                    parts.push(`<span class="chip-flag">Score ${Number(summary.sellSpikeScore).toFixed(2)}</span>`);
                }
                if (summary.confirmed != null) {
                    parts.push(`<span class="chip-flag ${summary.confirmed ? 'flag-yes' : 'flag-no'}">Dump ${summary.confirmed ? 'YES' : 'NO'}</span>`);
                }
                flags.innerHTML = parts.join(' ');
                chip.appendChild(flags);
            }
        } catch (_) { /* non-fatal */ }
        chip.addEventListener('click', () => presetSchedule({ symbol, token, qty }));
        container.appendChild(chip);
    });
}

async function refreshAuctionSummary(){
    if(!state.currentUser) return;
    try{
        const uname = encodeURIComponent(state.currentUser || '');
        const list = await fetchJson(`/api/auction/summary/${uname}`);
        const map = new Map();
        (list||[]).forEach(d => {
            if(d && d.tradingsymbol){ map.set(normalizeSymbol(d.tradingsymbol), d); }
        });
        state.analysisBySymbol = map;
    }catch(err){
        state.analysisBySymbol = new Map();
    }
}

function renderDepth(depths) {
    // Back-compat: if table exists, keep simple rendering
    const table = document.getElementById('depth-table');
    if (table) {
        table.innerHTML = '';
        if (!depths || depths.length === 0) {
            table.innerHTML = '<tr><td colspan="6" class="text-muted">No data</td></tr>';
        } else {
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
    }
    // Render multi-panels grid
    renderDepthPanels(depths);
}

function normalizeSymbol(sym){
    if(!sym) return sym;
    const idx = sym.indexOf(':');
    return idx > -1 ? sym.substring(idx+1) : sym;
}

function renderDepthPanels(depths){
    const container = document.getElementById('depth-panels');
    if(!container) return;
    container.innerHTML = '';
    const user = state.users.find(u => u.username === state.currentUser);
    const holdings = (user?.holdings || []).map(h => (h || '').split('|')[0]).filter(Boolean);
    const filter = state.holdingsFilter;
    const filtered = holdings.filter(h => {
        const exch = h.includes(':') ? h.split(':')[0].toUpperCase() : null;
        return filter === 'ALL' || exch === filter;
    });
    if(filtered.length === 0){
        container.innerHTML = '<div class="et-depth-muted">No holdings to show.</div>';
        return;
    }
    const bySymbol = new Map();
    (depths||[]).forEach(d => bySymbol.set(normalizeSymbol(d.tradingsymbol), d));
    const cards = [];
    filtered.forEach(h => {
        const sym = normalizeSymbol(h);
        const entry = bySymbol.get(sym);
        const card = renderSafeDepthCard(sym, entry);
        container.appendChild(card);
        cards.push(card);
    });
    // Save and apply paging (2 per page) — preserve current page across refreshes
    const prevPage = state.depthPage || 0;
    state.depthCards = cards;
    applyDepthPage(prevPage);
}

// Safe depth card renderer with robust fallbacks for missing fields
function renderSafeDepthCard(symbol, entry){
    const card = document.createElement('div');
    card.className = 'et-depth-panel small';
    const buy = entry?.buyQuantity || 0;
    const sell = entry?.sellQuantity || 0;
    const pressure = formatPressure(buy, sell);
    const ltp = entry?.ltp ?? '-';
    const ts = entry?.capturedAt ?? '-';
    const buys = Array.isArray(entry?.buyLevels) ? entry.buyLevels : [];
    const sells = Array.isArray(entry?.sellLevels) ? entry.sellLevels : [];
    const rows = Math.max(buys.length, sells.length, 5);
    let ladder = '';
    for (let i=0;i<rows;i++){
        const b = buys[i] || {}; const s = sells[i] || {};
        ladder += `<tr>
            <td class="et-bid">${b.price ?? ''}</td>
            <td class="et-bid">${b.orders ?? ''}</td>
            <td class="et-bid">${b.quantity ?? ''}</td>
            <td class="et-ask">${s.price ?? ''}</td>
            <td class="et-ask">${s.orders ?? ''}</td>
            <td class="et-ask">${s.quantity ?? ''}</td>
        </tr>`;
    }
    const dash = '&mdash;';
    card.innerHTML = `
        <div class="et-depth-header">
            <div class="et-depth-symbol">${symbol}</div>
            <div class="et-depth-price">${ltp}</div>
        </div>
        <table class="et-depth-ladder">
            <thead>
                <tr><th colspan="3">Bid</th><th colspan="3">Offer</th></tr>
                <tr><th>Price</th><th>Orders</th><th>Qty</th><th>Price</th><th>Orders</th><th>Qty</th></tr>
            </thead>
            <tbody>${ladder}</tbody>
        </table>
        <div class="et-depth-meta">
            <div class="meta"><span class="et-depth-muted">Open:</span> ${entry?.open ?? dash}</div>
            <div class="meta"><span class="et-depth-muted">Prev. Close:</span> ${entry?.prevClose ?? dash}</div>
            <div class="meta"><span class="et-depth-muted">Low:</span> ${entry?.low ?? dash}</div>
            <div class="meta"><span class="et-depth-muted">High:</span> ${entry?.high ?? dash}</div>
            <div class="meta"><span class="et-depth-muted">Volume:</span> ${entry?.volume ?? dash}</div>
            <div class="meta"><span class="et-depth-muted">Avg. price:</span> ${entry?.avgPrice ?? dash}</div>
            <div class="meta"><span class="et-depth-muted">Lower circuit:</span> ${entry?.lowerCircuit ?? dash}</div>
            <div class="meta"><span class="et-depth-muted">Upper circuit:</span> ${entry?.upperCircuit ?? dash}</div>
            <div class="meta"><span class="et-depth-muted">LTQ:</span> ${entry?.ltq ?? dash}</div>
            <div class="meta"><span class="et-depth-muted">LTT:</span> ${entry?.ltt ?? dash}</div>
            <div class="meta"><span class="et-depth-muted">Pressure:</span> ${pressure}</div>
            <div class="meta"><span class="et-depth-muted">Updated:</span> ${ts}</div>
        </div>`;
    return card;
}

function applyDepthPage(newPage){
    const cards = state.depthCards || [];
    const size = state.depthPageSize || 2;
    if (typeof newPage === 'number') state.depthPage = newPage;
    const totalPages = Math.max(1, Math.ceil(cards.length / size));
    if (state.depthPage < 0) state.depthPage = 0;
    if (state.depthPage > totalPages - 1) state.depthPage = totalPages - 1;
    const start = state.depthPage * size;
    const end = start + size;
    cards.forEach((el, idx) => {
        el.style.display = (idx >= start && idx < end) ? '' : 'none';
    });
    const prev = document.getElementById('depth-prev');
    const next = document.getElementById('depth-next');
    if (prev) prev.disabled = state.depthPage === 0;
    if (next) next.disabled = state.depthPage >= totalPages - 1;
}

function buildDepthCard(symbol, entry){
    const card = document.createElement('div');
    card.className = 'et-depth-panel small';
    const buy = entry?.buyQuantity || 0;
    const sell = entry?.sellQuantity || 0;
    const pressure = formatPressure(buy, sell);
    const ltp = entry?.ltp ?? '-';
    const ts = entry?.capturedAt ?? '-';
    const buys = Array.isArray(entry?.buyLevels) ? entry.buyLevels : [];
    const sells = Array.isArray(entry?.sellLevels) ? entry.sellLevels : [];
    const rows = Math.max(buys.length, sells.length, 5);
    let ladder = '';
    for (let i=0;i<rows;i++){
        const b = buys[i] || {}; const s = sells[i] || {};
        ladder += `<tr>
            <td class="et-bid">${b.price ?? ''}</td>
            <td class="et-bid">${b.orders ?? ''}</td>
            <td class="et-bid">${b.quantity ?? ''}</td>
            <td class="et-ask">${s.price ?? ''}</td>
            <td class="et-ask">${s.orders ?? ''}</td>
            <td class="et-ask">${s.quantity ?? ''}</td>
        </tr>`;
    }
    card.innerHTML = `
        <div class="et-depth-header">
            <div class="et-depth-symbol">${symbol}</div>
            <div class="et-depth-price">${ltp}</div>
        </div>
        <table class="et-depth-ladder">
            <thead>
                <tr><th colspan="3">Bid</th><th colspan="3">Offer</th></tr>
                <tr><th>Price</th><th>Orders</th><th>Qty</th><th>Price</th><th>Orders</th><th>Qty</th></tr>
            </thead>
            <tbody>${ladder}</tbody>
        </table>
        <div class="et-depth-meta">
            <div class="meta"><span class="et-depth-muted">Open:</span> ${entry?.open ?? '—'}</div>
            <div class="meta"><span class="et-depth-muted">Prev. Close:</span> ${entry?.prevClose ?? '—'}</div>
            <div class="meta"><span class="et-depth-muted">Low:</span> ${entry?.low ?? '—'}</div>
            <div class="meta"><span class="et-depth-muted">High:</span> ${entry?.high ?? '—'}</div>
            <div class="meta"><span class="et-depth-muted">Volume:</span> ${entry?.volume ?? '—'}</div>
            <div class="meta"><span class="et-depth-muted">Avg. price:</span> ${entry?.avgPrice ?? '—'}</div>
            <div class="meta"><span class="et-depth-muted">Lower circuit:</span> ${entry?.lowerCircuit ?? '—'}</div>
            <div class="meta"><span class="et-depth-muted">Upper circuit:</span> ${entry?.upperCircuit ?? '—'}</div>
            <div class="meta"><span class="et-depth-muted">LTQ:</span> ${entry?.ltq ?? '—'}</div>
            <div class="meta"><span class="et-depth-muted">LTT:</span> ${entry?.ltt ?? '—'}</div>
            <div class="meta"><span class="et-depth-muted">Pressure:</span> ${pressure}</div>
            <div class="meta"><span class="et-depth-muted">Updated:</span> ${ts}</div>
        </div>`;
    return card;
}

function presetSchedule(holding) {
    const symbol = typeof holding === 'string' ? holding : holding.symbol;
    const token = typeof holding === 'string' ? null : holding.token;
    const qty = typeof holding === 'string' ? null : holding.qty;
    document.getElementById('tradingsymbol').value = symbol;
    if (token) document.getElementById('instrumentToken').value = token;
    if (qty != null && !Number.isNaN(qty)) document.getElementById('quantity').value = qty;
    document.getElementById('instrumentToken').focus();
    // Also select for depth panel
    state.selectedSymbol = symbol;
    refreshDepth();
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
        const manualEnabled = document.getElementById('manual-mode-toggle')?.checked;
        const manualTime = document.getElementById('manualTime')?.value;
        const payload = {
            tradingsymbol: document.getElementById('tradingsymbol').value.trim().toUpperCase(),
            instrumentToken: document.getElementById('instrumentToken').value.trim(),
            quantity: Number(document.getElementById('quantity').value),
            side: document.getElementById('side').value,
            sessionSlot: document.getElementById('sessionSlot').value,
            tradeDate: document.getElementById('tradeDate').value,
            limitPrice: document.getElementById('limitPrice').value ? Number(document.getElementById('limitPrice').value) : null,
            autoRepeat: document.getElementById('autoRepeat').checked,
            cancelOpenOrdersBeforeExecution: document.getElementById('cancelOrders').checked,
            scheduledTime: (manualEnabled && manualTime) ? manualTime + ':00' : null
        };
        try {
            const resp = await fetchJson(`/api/schedules/${state.currentUser}`, {
                method: 'POST',
                body: JSON.stringify(payload)
            });
            form.reset();
            document.getElementById('tradeDate').value = new Date().toISOString().split('T')[0];
            if (resp && resp.rolledToNextSlot) {
                const time = resp.rolledTimeIst || (resp.nextExecutionTime ? new Date(resp.nextExecutionTime).toLocaleTimeString([], {hour: '2-digit', minute: '2-digit'}) : 'next slot');
                showToast(`Scheduled for next available slot at ${time}`);
            } else {
                showToast('Schedule created');
            }
            refreshSchedules();
        } catch (err) {
            showScheduleErrorModal(err?.message || 'Scheduled time is in the past. Please enable Manual Time and choose a future time.');
        }
    });

    // Manual scheduling toggle
    const manualToggle = document.getElementById('manual-mode-toggle');
    const manualTimeInput = document.getElementById('manualTime');
    if (manualToggle && manualTimeInput) {
        manualToggle.addEventListener('change', () => {
            manualTimeInput.disabled = !manualToggle.checked;
            if (manualToggle.checked && !manualTimeInput.value) {
                const now = new Date();
                now.setMinutes(now.getMinutes() + 1);
                manualTimeInput.value = now.toTimeString().slice(0,5);
            }
        });
    }

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
    // Retry buttons in card headers
    const doRetry = async () => {
        try {
            const btns = document.querySelectorAll('#retry-depth,#retry-upcoming,#retry-executed,#retry-holdings');
            btns.forEach(b => b && (b.disabled = true));
            await refreshSchedules();
            await refreshDepth();
            await fetchSessionStatus();
        } finally {
            const btns = document.querySelectorAll('#retry-depth,#retry-upcoming,#retry-executed,#retry-holdings');
            btns.forEach(b => b && (b.disabled = false));
        }
    };
    ['retry-depth','retry-upcoming','retry-executed','retry-holdings'].forEach(id => {
        const el = document.getElementById(id);
        if (el) el.addEventListener('click', doRetry);
    });
    // Depth slider navigation
    const prev = document.getElementById('depth-prev');
    const next = document.getElementById('depth-next');
    if (prev) prev.addEventListener('click', () => applyDepthPage(state.depthPage - 1));
    if (next) next.addEventListener('click', () => applyDepthPage(state.depthPage + 1));
}

function showScheduleErrorModal(message){
    try{
        const text = document.getElementById('schedule-error-text');
        if (text) text.textContent = message;
        const modalEl = document.getElementById('schedule-error-modal');
        if (!modalEl || !window.bootstrap || !bootstrap.Modal) { alert(message); return; }
        const modal = new bootstrap.Modal(modalEl);
        modal.show();
        const btn = document.getElementById('schedule-error-manual-btn');
        if (btn){
            btn.onclick = () => {
                try {
                    const toggle = document.getElementById('manual-mode-toggle');
                    const input = document.getElementById('manualTime');
                    if (toggle && input){
                        toggle.checked = true;
                        input.disabled = false;
                        if (!input.value){
                            const now = new Date();
                            now.setMinutes(now.getMinutes() + 2);
                            input.value = now.toTimeString().slice(0,5);
                        }
                        input.focus();
                    }
                } catch(_){}
            };
        }
    }catch(_){ alert(message); }
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



















function showToast(message){
    try{
        const container = document.getElementById('toast-container');
        if (!container || !window.bootstrap) { console.log(message); return; }
        const el = document.createElement('div');
        el.className = 'toast align-items-center text-bg-dark border-0';
        el.setAttribute('role', 'alert');
        el.setAttribute('aria-live', 'assertive');
        el.setAttribute('aria-atomic', 'true');
        el.innerHTML = `<div class="d-flex"><div class="toast-body">${message}</div><button type="button" class="btn-close btn-close-white me-2 m-auto" data-bs-dismiss="toast" aria-label="Close"></button></div>`;
        container.appendChild(el);
        const t = new bootstrap.Toast(el, { delay: 3000 });
        t.show();
        el.addEventListener('hidden.bs.toast', () => el.remove());
    } catch(_) { console.log(message); }
}
