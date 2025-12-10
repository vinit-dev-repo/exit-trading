const state = {
    users: [],
    currentUser: null,
    refreshHandle: null,
    scheduleHandle: null,
    depthHandle: null,
    sessionHandle: null,
    newsHandle: null,
    newsRetry: null,
    holdingsFilter: 'ALL',
    sessionActive: false,
    selectedSymbol: null,
    depthPage: 0,
    depthPageSize: 3,
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

// Indian digit-grouping formatter for readability (e.g., 1,23,456)
function formatInr(x){
    if (x === null || x === undefined) return '';
    const n = Number(x);
    if (!Number.isFinite(n)) return String(x);
    return n.toLocaleString('en-IN');
}

async function fetchNews(){
    try{
        const container = document.getElementById('news-panel');
        if (!container) return;
        const resp = await fetch('/api/news');
        if (!resp.ok) throw new Error(`News fetch failed ${resp.status}`);
        const data = await resp.json();
        const items = data?.data?.latest_news || [];
        renderNews(items);
    }catch(err){
        const container = document.getElementById('news-panel');
        if (container) container.innerHTML = '<div class="text-muted small">News unavailable (will retry)...</div>';
        // Gentle retry to ride over transient 5xx
        try { clearTimeout(state.newsRetry); } catch(_) {}
        state.newsRetry = setTimeout(fetchNews, 15000);
        console.warn('News fetch error (retrying)', err?.message || err);
    }
}

function renderNews(items){
    const container = document.getElementById('news-panel');
    if (!container) return;
    if (!items || items.length === 0){
        container.innerHTML = '<div class="text-muted small">No news.</div>';
        return;
    }
    // Duplicate list to make scrolling seamless and adjust duration based on length
    const repeat = 2; // exactly 2 copies so -50% translate loops perfectly
    const list = [];
    for (let r = 0; r < repeat; r++){
        list.push(...items);
    }
    const rows = list.map(n => {
        const title = n?.news_object?.title || '-';
        const text = n?.news_object?.text || '';
        const sent = (n?.news_object?.overall_sentiment || '').toLowerCase();
        const sentBadge = sent === 'positive' ? 'bg-success' : (sent === 'negative' ? 'bg-danger' : 'bg-secondary');
        const ts = n?.publish_date ? formatIst(n.publish_date) : '-';
        const sym = n?.display_symbol || n?.sm_symbol || '';
        return `<div class="news-item">
            <div class="d-flex justify-content-between">
                <span class="fw-bold">${sym}</span>
                <span class="badge ${sentBadge}">${sent || 'neutral'}</span>
            </div>
            <div class="news-title">${title}</div>
            <div class="news-time text-muted small">${ts}</div>
            <div class="news-text small">${text}</div>
        </div>`;
    }).join('');
    // Faster cadence so it starts immediately and loops with minimal gap
    const duration = Math.max(10, list.length * 1.5);
    container.innerHTML = `<div class="news-scroll" style="--news-duration:${duration}s">${rows}</div>`;
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
    try { await updateHoldingsImpact(); } catch(_) {}
    await fetchSessionStatus();
}

// Format to DD-MON-YY HH:mm:ss IST regardless of browser timezone
function formatIst(value){
    if (value === null || value === undefined || value === '-') return '-';
    let d;
    try {
        if (typeof value === 'number') {
            d = new Date(value > 1e12 ? value : value * 1000);
        } else if (typeof value === 'string' && /^\d+$/.test(value.trim())) {
            const n = Number(value.trim());
            d = new Date(n > 1e12 ? n : n * 1000);
        } else {
            d = new Date(value);
        }
        if (isNaN(d)) return '-';
        const parts = new Intl.DateTimeFormat('en-GB', {
            timeZone: 'Asia/Kolkata',
            day: '2-digit', month: 'short', year: '2-digit',
            hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false
        }).formatToParts(d);
        let dd='01', mon='JAN', yy='00', hh='00', mm='00', ss='00';
        for (const p of parts){
            if (p.type === 'day') dd = p.value;
            else if (p.type === 'month') mon = p.value.toUpperCase();
            else if (p.type === 'year') yy = p.value;
            else if (p.type === 'hour') hh = p.value;
            else if (p.type === 'minute') mm = p.value;
            else if (p.type === 'second') ss = p.value;
        }
        return `${dd}-${mon}-${yy} ${hh}:${mm}:${ss} IST`;
    } catch { return '-'; }
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
    // After depth data is in state, compute holdings impact
    try { await updateHoldingsImpact(); } catch(_) {}
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
        chip.dataset.symbol = symbol;
        if (qty != null) chip.dataset.qty = qty;
        chip.title = [
            token ? `Token: ${token}` : null
                ].filter(Boolean).join(' ')
        chip.innerHTML = `
            <div class="chip-line"><strong>${symbol}</strong>${exchange ? ` <span class="text-muted">(${exchange})</span>` : ''} </strong>${product ? ` <span class="text-muted">${product}</span>` : ''} </div>
            <div class="chip-meta text-muted">${
                [
                    qty != null ? `Qty: ${qty}` : null,
                    avg != null ? `Avg: ${avg.toFixed(2)}` : null,
                    last != null ? `LTP: ${last.toFixed(2)}` : null,
                    pct != null ? `Day(%): ${pct.toFixed(2)}` : null,
                    pnl != null ? `P&L: ${Number(pnl).toFixed(2)}` : null,
                ].filter(Boolean).join(' ')
            }</div>`;
        
        // Restyle chip-meta as badge-like flags and split rows
        try {
            const meta = chip.querySelector('.chip-meta');
            const row1 = [
                (qty != null ? `<span class=\"chip-flag\">Qty: ${qty}</span>` : null),
                (avg != null ? `<span class=\"chip-flag\">Avg: ${avg.toFixed(2)}</span>` : null),
                (last != null ? `<span class=\"chip-flag\">LTP: ${last.toFixed(2)}</span>` : null),
            ].filter(Boolean).join(' ');
            const row2 = [
                (pct != null ? `<span class=\"chip-flag\">Day(%): ${pct.toFixed(2)}</span>` : null),
                (pnl != null ? `<span class=\"chip-flag\">P&L: ${Number(pnl).toFixed(2)}</span>` : null),
            ].filter(Boolean).join(' ');
            if (meta) {
                meta.classList.remove('text-muted');
                meta.innerHTML = `${row1 ? `<div class=\"chip-meta-row\">${row1}</div>` : ''}${row2 ? `<div class=\"chip-meta-row\">${row2}</div>` : ''}`;
            }
        } catch(_) {}
        // Removed OBI/Swing/Score/Dump chips on holdings for cleaner UI
        // Modern Liquidity Impact mini-widget inside holding card
        try {
            const impact = document.createElement('div');
            impact.className = 'chip-impact';
            impact.innerHTML = '<span class="impact-badge impact-low" data-role="sell">SELL --</span> <span class="impact-badge impact-low" data-role="buy">BUY --</span>';
            chip.appendChild(impact);

        } catch(_) {}
        chip.addEventListener('click', () => presetSchedule({ symbol, token, qty }));
        container.appendChild(chip);
        try {
            const hr = document.createElement('hr');
            hr.className = 'border-dark';
            container.appendChild(hr);
        } catch (_) { /* ignore */ }
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
    state.depthDataBySymbol = bySymbol;
    const cards = [];
    filtered.forEach(h => {
        const sym = normalizeSymbol(h);
        const entry = bySymbol.get(sym);
        const card = renderSafeDepthCard(sym, entry);
        container.appendChild(card);
        cards.push(card);
    });
    // Save and apply paging (2 per page) ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â preserve current page across refreshes
    const prevPage = state.depthPage || 0;
    state.depthCards = cards;
    applyDepthPage(prevPage);
    
}

async function updateHoldingsImpact(){
    try{
        const chips = Array.from(document.querySelectorAll('#holdings-list .holding-chip'));
        if (!chips.length) return;
        for (const chip of chips){
            const sym = chip.dataset.symbol;
            const entry = state.depthDataBySymbol?.get?.(normalizeSymbol(sym));
            if (!entry) continue;
            const exitQty = Number(chip.dataset.qty || 0);
            const payload = { ...entry, exitQuantity: Number.isFinite(exitQty) ? exitQty : undefined };
            const resp = await fetchJson('/api/liquidity-impact/compute', { method: 'POST', body: JSON.stringify([payload]) });
            const r = Array.isArray(resp) ? resp[0] : null;
            const container = chip.querySelector('.chip-impact');
            if (!r || !container) continue;
            const legend = (r.scores?.legend || 'Low').toLowerCase();
            const legendCls = legend.includes('high') ? 'impact-high' : (legend.includes('moderate') ? 'impact-mod' : 'impact-low');
            const sellScore = Math.round((r.scores?.sell ?? 0) * 10) / 10;
            const buyScore = Math.round((r.scores?.buy ?? 0) * 10) / 10;
            const fmt = (v) => (v == null || Number.isNaN(v)) ? '-' : (typeof v === 'number' ? (Math.round(v * 100) / 100).toFixed(2) : String(v));
            const fmtBps = (v) => (v == null || Number.isNaN(v)) ? '-' : `${(Math.round(v * 10) / 10).toFixed(1)} bps/s`;
            const fmtSec = (v) => (v == null || Number.isNaN(v)) ? '-' : `${Math.round(v)}s`;
            const q = (dp) => (dp && typeof dp.shares === 'number') ? dp.shares : 0;
            const p = (dp) => (dp && typeof dp.price === 'number') ? fmt(dp.price) : '-';
            const fmtQty = (v) => (v == null || Number.isNaN(v)) ? '-' : formatInr(v);
            const fmtOrder = (label, qty, orders, price) => {
                if (qty == null || Number.isNaN(qty)) return `${label}: -`;
                const parts = [`${label}: ${fmtQty(qty)}`];
                if (orders != null && orders > 0) parts.push(`per ${formatInr(orders)}`);
                if (price != null && Number.isFinite(price)) parts.push(`@${fmt(price)}`);
                return parts.join(' ');
            };
            const bestBid = r.best?.bid, bestAsk = r.best?.ask, spr = r.best?.spread;
            const L = r.band?.L, U = r.band?.U;
            const tick = r.tick;
            const qref = r.qref;
            const dumpFlag = !!r.micro?.dumpSell;
            const bandTouch = (typeof bestBid === 'number' && typeof L === 'number' && typeof tick === 'number' && bestBid <= L + tick + 1e-9);
            const noBids = !(typeof bestBid === 'number' && bestBid > 0);
            const tts = r.timeToBandSellSec;
            const drift = r.driftBps;
            const ticksToBand = (typeof bestBid === 'number' && typeof L === 'number' && typeof tick === 'number' && tick > 0)
                ? Math.max(0, Math.floor((bestBid - L) / tick))
                : Infinity;
            const condTime = (typeof tts === 'number' && tts > 0 && tts <= 120) || (typeof drift === 'number' && drift <= -3);
            const condDepth = (() => {
                const qband = r.deltas?.sell?.toBand?.shares;
                if (typeof qband === 'number' && typeof qref === 'number' && qref > 0 && qband <= 3 * qref) return true;
                return Number.isFinite(ticksToBand) && ticksToBand <= 5;
            })();
            const condPressure = (() => {
                const obi = r.micro?.obiPct;
                const combinedSell = r.combined?.sell;
                return (typeof obi === 'number' && obi <= -20) || (typeof combinedSell === 'number' && combinedSell >= 50);
            })();
            const depthConf = (typeof r.depthConfidence === 'number') ? r.depthConfidence : 1.0;
            const exitSoon = !dumpFlag && depthConf >= 0.7 && [condTime, condDepth, condPressure].filter(Boolean).length >= 2;
            let decisionLabel = 'Not yet';
            let decisionStyle = 'background:#198754;color:white;';
            let decisionTooltip = 'Green = safe, Orange = likely near band soon, Red = exit now';
            if (exitSoon) { decisionLabel = 'EXIT Soon'; decisionStyle = 'background:#fd7e14;color:white;'; decisionTooltip = 'Time-to-band =120s or drift =-3bps/s, plus depth or pressure risk; two of three signals needed'; }
            if (dumpFlag) { decisionLabel = 'EXIT NOW'; decisionStyle = 'background:#dc3545;color:white;'; decisionTooltip = 'Dump criteria met (score above baseline with time/trend/pressure confirmation)'; }
            // Build compact table
            const legendLabel = dumpFlag ? 'High' : (r.combined?.legend || r.scores?.legend || 'Low');
            container.innerHTML = `
                <div class="chip-impact-head">
                  <span class="score-chip">Score <i class="bi bi-info-circle" data-bs-toggle="tooltip" title="0&ndash;100 mix of ticks moved, VWAP % vs band, depth coverage, spread fragility, IOI; stage factor: ESM&ndash;1=1.00, ESM&ndash;2 entry=1.20, buffer/uncross=1.10."></i> &nbsp; Sell ${sellScore} &middot; Buy ${buyScore}</span>
                  <span class="legend-chip ${dumpFlag ? 'impact-high' : legendCls}">${legendLabel}</span>
                  <span class="badge ms-2" style="${decisionStyle}" data-bs-toggle="tooltip" title="${decisionTooltip}">Sell: ${decisionLabel}</span>
                </div>
                <table class="chip-impact-table">
                  <tr><td>Tick <i class="bi bi-info-circle" data-bs-toggle="tooltip" title="Tick size from instrument master; fallback heuristics if missing"></i></td><td>${fmt(tick)}</td><td class="right">Qref <i class="bi bi-info-circle" data-bs-toggle="tooltip" title="Reference size (uses holding qty when available; else max(ltq, 0.1% of visible) clipped)"></i></td><td class="right">${qref ?? '-'} </td></tr>
                  <tr><td>B1/A1 <i class="bi bi-info-circle" data-bs-toggle="tooltip" title="Best bid/ask and spread at snapshot"></i></td><td colspan="3">${fmt(bestBid)} / ${fmt(bestAsk)} <span class="muted">(spr ${fmt(spr)})</span></td></tr>
                  <tr><td>Max order</td><td colspan="3">${fmtOrder('Bid', r.maxBuyOrderQty, r.maxBuyOrderCount, r.maxBuyOrderPrice)} / ${fmtOrder('Ask', r.maxSellOrderQty, r.maxSellOrderCount, r.maxSellOrderPrice)}</td></tr>
                  <tr class="${(bandTouch || dumpFlag || noBids) ? 'impact-high' : legendCls}"><td>&Delta;SELL <i class="bi bi-info-circle" data-bs-toggle="tooltip" title="Minimal shares to move by 1 tick or to band; shows resulting price"></i></td><td>1t ${q(r.deltas?.sell?.oneTick)}@${p(r.deltas?.sell?.oneTick)}</td><td>&rarr; Band</td><td class="right">${q(r.deltas?.sell?.toBand)}@${p(r.deltas?.sell?.toBand)}</td></tr>
                  <tr class="${(bandTouch || dumpFlag || noBids) ? 'impact-high' : legendCls}"><td>&Delta;BUY <i class="bi bi-info-circle" data-bs-toggle="tooltip" title="Minimal shares to move by 1 tick or to band; shows resulting price"></i></td><td>${noBids ? 'No bids' : `1t ${q(r.deltas?.buy?.oneTick)}@${p(r.deltas?.buy?.oneTick)}`}</td><td>&rarr; Band</td><td class="right">${noBids ? '-' : `${q(r.deltas?.buy?.toBand)}@${p(r.deltas?.buy?.toBand)}`}</td></tr>
                  <!-- Score moved to header -->

                </table>`;
            // Prefer combined legend and append micro chips row
            try{
                const chipLegendEl = container.querySelector('.legend-chip');
                if (chipLegendEl) chipLegendEl.textContent = (r.combined?.legend || r.scores?.legend || 'Low');
                const tbl = container.querySelector('.chip-impact-table');
                if (tbl){
                    const tr = document.createElement('tr');
                    const obiStr = (typeof r.micro?.obiPct==='number') ? ((r.micro.obiPct>0?'+':'')+r.micro.obiPct+'%') : '-';
                    const swingStr = (typeof r.micro?.swingSell==='number') ? r.micro.swingSell.toFixed(2) : '-';
                    const microStr = (typeof r.micro?.microSell==='number') ? r.micro.microSell.toFixed(2) : '-';
                    const dumpStr = r.micro?.dumpSell ? 'YES' : 'NO';
                    const driftStr = fmtBps(r.driftBps);
                    const ltqStr = fmt(r.ltqPerSec) + '/s';
                    const tts = fmtSec(r.timeToBandSellSec);
                    const ttb = fmtSec(r.timeToBandBuySec);
                    tr.innerHTML = `<td>Micro</td><td colspan="3">OBI ${obiStr} &middot; Swing ${swingStr} &middot; Micro ${microStr} &middot; Drift ${driftStr} &middot; LTQ ${ltqStr} &middot; TTB S ${tts} / B ${ttb} &middot; Dump ${dumpStr}</td>`;
                    tbl.appendChild(tr);
                }
            } catch(_){}
        }
        // Legend icon removed; per-field tooltips are provided in-table
    } catch(_){}
}

// Safe depth card renderer with robust fallbacks for missing fields
function renderSafeDepthCard(symbol, entry){
    const card = document.createElement('div');
    card.className = 'et-depth-panel small';
    card.dataset.symbol = symbol;
    const buy = entry?.buyQuantity || 0;
    const sell = entry?.sellQuantity || 0;
    const pressure = formatPressure(buy, sell);
    const ltp = entry?.ltp ?? '-';
    const ts = entry?.capturedAt ?? '-';
    const tsFmt = formatIst(ts);
    const lttFmt = formatIst(entry?.ltt);
    const lttDisp = (lttFmt && lttFmt !== '-') ? lttFmt : tsFmt;
    const buys = Array.isArray(entry?.buyLevels) ? entry.buyLevels : [];
    const sells = Array.isArray(entry?.sellLevels) ? entry.sellLevels : [];
    const rows = Math.max(buys.length, sells.length, 5);
    let ladder = '';
    for (let i=0;i<rows;i++){
        const b = buys[i] || {}; const s = sells[i] || {};
        ladder += `<tr>
            <td class="et-bid">${b.price ?? ''}</td>
            <td class="et-bid">${b.orders ?? ''}</td>
            <td class="et-bid">${formatInr(b.quantity ?? '')}</td>
            <td class="et-ask">${s.price ?? ''}</td>
            <td class="et-ask">${s.orders ?? ''}</td>
            <td class="et-ask">${formatInr(s.quantity ?? '')}</td>
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
            <div class="meta"><span class="et-depth-muted">LTT:</span> ${lttDisp}</div>
            <div class="meta"><span class="et-depth-muted">Pressure:</span> ${pressure}</div>
            <div class="meta"><span class="et-depth-muted">Updated:</span> ${tsFmt}</div>
        </div>`;
    return card;
}

function applyDepthPage(newPage){
    const cards = state.depthCards || [];
    const size = state.depthPageSize || 3;
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
    // Impact shown on holdings chips; depth paging is independent
}

function buildDepthCard(symbol, entry){
    const card = document.createElement('div');
    card.className = 'et-depth-panel small';
    const buy = entry?.buyQuantity || 0;
    const sell = entry?.sellQuantity || 0;
    const pressure = formatPressure(buy, sell);
    const ltp = entry?.ltp ?? '-';
    const ts = entry?.capturedAt ?? '-';
    const tsFmt = formatIst(ts);
    const lttFmt = formatIst(entry?.ltt);
    const lttDisp = (lttFmt && lttFmt !== '-') ? lttFmt : tsFmt;
    const buys = Array.isArray(entry?.buyLevels) ? entry.buyLevels : [];
    const sells = Array.isArray(entry?.sellLevels) ? entry.sellLevels : [];
    const rows = Math.max(buys.length, sells.length, 5);
    let ladder = '';
    for (let i=0;i<rows;i++){
        const b = buys[i] || {}; const s = sells[i] || {};
        ladder += `<tr>
            <td class="et-bid">${b.price ?? ''}</td>
            <td class="et-bid">${b.orders ?? ''}</td>
            <td class="et-bid">${formatInr(b.quantity ?? '')}</td>
            <td class="et-ask">${s.price ?? ''}</td>
            <td class="et-ask">${s.orders ?? ''}</td>
            <td class="et-ask">${formatInr(s.quantity ?? '')}</td>
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
            <div class="meta"><span class="et-depth-muted">Open:</span> ${entry?.open ?? 'ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â'}</div>
            <div class="meta"><span class="et-depth-muted">Prev. Close:</span> ${entry?.prevClose ?? 'ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â'}</div>
            <div class="meta"><span class="et-depth-muted">Low:</span> ${entry?.low ?? 'ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â'}</div>
            <div class="meta"><span class="et-depth-muted">High:</span> ${entry?.high ?? 'ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â'}</div>
            <div class="meta"><span class="et-depth-muted">Volume:</span> ${entry?.volume ?? 'ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â'}</div>
            <div class="meta"><span class="et-depth-muted">Avg. price:</span> ${entry?.avgPrice ?? 'ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â'}</div>
            <div class="meta"><span class="et-depth-muted">Lower circuit:</span> ${entry?.lowerCircuit ?? 'ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â'}</div>
            <div class="meta"><span class="et-depth-muted">Upper circuit:</span> ${entry?.upperCircuit ?? 'ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â'}</div>
            <div class="meta"><span class="et-depth-muted">LTQ:</span> ${entry?.ltq ?? 'ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â'}</div>
            <div class="meta"><span class="et-depth-muted">LTT:</span> ${lttDisp}</div>
            <div class="meta"><span class="et-depth-muted">Pressure:</span> ${pressure}</div>
            <div class="meta"><span class="et-depth-muted">Updated:</span> ${tsFmt}</div>
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
            const expiryIst = expiryMs ? formatIst(expiryMs) : '-';
            badge.innerHTML = `<i class="bi bi-check-circle me-1"></i>Active ${userLabel ? userLabel + ' ' : ''}— Expires ${expiryIst}`;
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
    if (state.newsHandle) clearInterval(state.newsHandle);
    // Schedules: every 15s, Depth: every 8s, Session status: every 60s
    state.scheduleHandle = setInterval(refreshSchedules, 15000);
    state.depthHandle = setInterval(refreshDepth, 8000);
    state.sessionHandle = setInterval(fetchSessionStatus, 60000);
    // News: every 5 minutes
    state.newsHandle = setInterval(fetchNews, 300000);
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
    // Kick off news fetch immediately (don’t wait for other calls)
    fetchNews().catch(() => {});
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



















