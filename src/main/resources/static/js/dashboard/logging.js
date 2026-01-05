import { fetchJson } from '../api.js';
import { formatPressure, formatInr, formatIst } from '../formatting.js';
import { state } from './state.js';
import { showToast } from './ui.js';

function isStaleSnapshot(item) {
    try {
        const snap = item?.lastSnapshot;
        const ts = snap?.capturedAt || item?.lastLoggedAt;
        if (!ts) return true;
        const age = Date.now() - Date.parse(ts);
        return !Number.isFinite(age) || age > 30000;
    } catch (_) { return true; }
}

async function ensureLoggingSnapshots() {
    if (state.loggingCatchup) return;
    const missing = (state.loggingScrips || []).filter(s => !s.lastSnapshot || isStaleSnapshot(s)).slice(0, 5);
    if (missing.length === 0) return;
    state.loggingCatchup = true;
    try {
        await Promise.all(missing.map(s => captureLoggingOne(s.id)));
    } catch (_) {
        /* ignore catch-up errors */
    } finally {
        state.loggingCatchup = false;
    }
}

function isLoggingWindowOpenIst() {
    try {
        const parts = new Intl.DateTimeFormat('en-GB', {
            timeZone: 'Asia/Kolkata',
            hour: '2-digit',
            minute: '2-digit',
            weekday: 'short',
            hour12: false
        }).formatToParts(new Date());
        let h = 0, m = 0, wd = '';
        for (const p of parts) {
            if (p.type === 'hour') h = parseInt(p.value, 10);
            else if (p.type === 'minute') m = parseInt(p.value, 10);
            else if (p.type === 'weekday') wd = p.value || '';
        }
        const weekend = wd.toLowerCase().startsWith('sat') || wd.toLowerCase().startsWith('sun');
        if (weekend) return false;
        const mins = (h * 60) + m;
        return mins >= (9 * 60 + 15) && mins <= (15 * 60 + 30);
    } catch (_) { return false; }
}

function renderLoggingWindowStatus() {
    const pill = document.getElementById('logging-window-pill');
    if (!pill) return;
    const open = isLoggingWindowOpenIst();
    pill.className = 'badge ' + (open ? 'bg-success' : 'bg-secondary');
    pill.textContent = open ? 'Window open (09:15-15:30 IST)' : 'Window closed (09:15-15:30 IST)';
}

function renderLoggingSearchResults(results) {
    const container = document.getElementById('logging-search-results');
    if (!container) return;
    if (!results || results.length === 0) {
        container.innerHTML = '<div class="text-muted">No matches. Try a different keyword.</div>';
        return;
    }
    const rows = results.map(r => {
        const token = r.token || '';
        const meta = [
            token ? `Token ${token}` : null,
            r.tickSize != null ? `Tick ${r.tickSize}` : null,
            r.lowerCircuit != null ? `LC ${r.lowerCircuit}` : null,
            r.upperCircuit != null ? `UC ${r.upperCircuit}` : null
        ].filter(Boolean).join(' | ');
        return `<div class="logging-result-row">
            <div>
                <div class="fw-bold text-light">${r.exchange || '-'}:${r.symbol}</div>
                <div class="text-muted small">${meta || 'No metadata'}</div>
            </div>
            <button class="btn btn-sm btn-outline-success" data-action="add-logging" data-symbol="${r.symbol}" data-exchange="${r.exchange || ''}" data-token="${token}">Add</button>
        </div>`;
    }).join('');
    container.innerHTML = rows;
}

async function searchLoggingScrips(query) {
    const container = document.getElementById('logging-search-results');
    if (!query || query.trim().length < 2) {
        if (container) container.innerHTML = '<div class="text-muted">Enter at least 2 characters.</div>';
        return;
    }
    try {
        const res = await fetchJson(`/api/logging/search?q=${encodeURIComponent(query.trim())}`);
        state.loggingSearchResults = res || [];
        renderLoggingSearchResults(state.loggingSearchResults);
    } catch (err) {
        if (container) container.innerHTML = `<div class="text-muted small">Search failed: ${err?.message || err}</div>`;
    }
}

export async function fetchLoggingScrips() {
    if (!state.currentUser) return;
    try {
        const data = await fetchJson(`/api/logging/scrips/${encodeURIComponent(state.currentUser)}`);
        state.loggingScrips = data || [];
        renderLoggingScrips();
        ensureLoggingSnapshots();
    } catch (err) {
        const list = document.getElementById('logging-scrips-list');
        if (list) list.innerHTML = `<div class="text-muted small">Logging data unavailable: ${err?.message || err}</div>`;
    } finally {
        renderLoggingWindowStatus();
        const badge = document.getElementById('logging-count-badge');
        if (badge) badge.textContent = (state.loggingScrips || []).length;
    }
}

async function addLoggingScrip(payload) {
    if (!state.currentUser) { showToast('Select a user first'); return; }
    if (!payload || !payload.tradingsymbol) { showToast('Pick a symbol to add'); return; }
    const body = {
        tradingsymbol: payload.tradingsymbol.toUpperCase(),
        exchange: payload.exchange || (document.getElementById('logging-exchange')?.value || 'NSE'),
        instrumentToken: payload.instrumentToken || ''
    };
    try {
        const created = await fetchJson(`/api/logging/scrips/${encodeURIComponent(state.currentUser)}`, {
            method: 'POST',
            body: JSON.stringify(body)
        });
        if (created && created.id) {
            await captureLoggingOne(created.id);
        } else {
            await fetchLoggingScrips();
        }
        showToast(`Added ${body.exchange}:${body.tradingsymbol}`);
    } catch (err) {
        showToast(err?.message || 'Unable to add scrip');
    }
}

async function toggleLoggingScrip(id, active) {
    if (!state.currentUser || !id) {
        showToast('System error: User or ID missing');
        return;
    }
    try {
        const current = String(active) === 'true';
        const target = !current;
        await fetchJson(`/api/logging/scrips/${encodeURIComponent(state.currentUser)}/${id}?active=${target}`, {
            method: 'PUT'
        });
        await fetchLoggingScrips();
        showToast(target ? 'Resumed logging' : 'Paused logging');
    } catch (err) {
        showToast('Toggle failed: ' + (err.message || 'Unknown error'));
    }
}

async function captureLoggingOne(id) {
    if (!state.currentUser || !id) return;
    try {
        const res = await fetchJson(`/api/logging/scrips/${encodeURIComponent(state.currentUser)}/${id}/capture`, { method: 'POST' });
        if (res) {
            const idx = state.loggingScrips.findIndex(s => s.id === id || String(s.id) === String(id));
            if (idx > -1) state.loggingScrips[idx] = res;
            else state.loggingScrips.push(res);
        }
        renderLoggingScrips();
    } catch (err) {
        showToast(err?.message || 'Capture failed');
    }
}

function renderLoggingScrips() {
    const list = document.getElementById('logging-scrips-list');
    if (!list) return;
    list.innerHTML = '';
    list.className = state.loggingLayout === 'grid' ? 'logging-grid' : 'logging-list';
    const data = (state.loggingScrips || []).slice().sort((a, b) => {
        const getRatio = (item) => {
            const s = item.lastSnapshot || {};
            const bQ = Number(s.buyQuantity || 0);
            const sQ = Number(s.sellQuantity || 0);
            const total = bQ + sQ;
            if (total === 0) return 0;
            return bQ / total;
        };

        const ratioA = getRatio(a);
        const ratioB = getRatio(b);

        if (Math.abs(ratioA - ratioB) > 0.01) {
            return ratioB - ratioA;
        }

        const ltpA = Number(a.lastSnapshot?.ltp || 0);
        const ltpB = Number(b.lastSnapshot?.ltp || 0);
        const vA = ltpA > 0 ? ltpA : Number.MAX_VALUE;
        const vB = ltpB > 0 ? ltpB : Number.MAX_VALUE;

        return vA - vB;
    });

    if (data.length === 0) {
        list.innerHTML = '<div class="text-muted">No scrips added yet.</div>';
        return;
    }
    const badge = document.getElementById('logging-count-badge');
    if (badge) badge.textContent = data.length;
    data.forEach(item => {
        const snap = item.lastSnapshot || {};
        const card = document.createElement('div');
        const exch = (item.exchange || '').toUpperCase();
        const symbol = item.tradingsymbol || '-';
        const cardExchClass = exch === 'BSE' ? 'logging-card-bse' : 'logging-card-nse';
        card.className = state.loggingLayout === 'grid'
            ? `logging-card-compact ${cardExchClass}`
            : `logging-chip ${cardExchClass}`;
        const added = item.addedAt ? formatIst(item.addedAt) : '-';
        const logged = item.lastLoggedAt ? formatIst(item.lastLoggedAt) : (snap.capturedAt ? formatIst(snap.capturedAt) : '-');
        const status = item.active ? '<span class="badge bg-success">Active</span>' : '<span class="badge bg-secondary">Paused</span>';
        const token = item.instrumentToken || '-';
        const num = (val) => {
            const n = Number(val);
            return Number.isFinite(n) ? n : null;
        };
        const formatPrice = (val) => {
            const n = num(val);
            if (n === null) return '-';
            return n.toLocaleString('en-IN', { minimumFractionDigits: 0, maximumFractionDigits: 2 });
        };

        const buyQty = Number(snap.buyQuantity ?? 0);
        const sellQty = Number(snap.sellQuantity ?? 0);
        const totalQty = buyQty + sellQty;
        const buyPct = totalQty > 0 ? Math.round((buyQty / totalQty) * 10000) / 100 : 50;
        const sellPct = totalQty > 0 ? Math.max(0, 100 - buyPct) : 50;

        const badgeTop = [];
        const badgeBottom = [];
        const ltpVal = num(snap.ltp);
        const prevVal = num(snap.prevClose);
        let openVal = num(snap.open);
        if (openVal === null || openVal === 0) openVal = num(snap.ltp);
        let lowVal = num(snap.low);
        if (lowVal === null) lowVal = num(snap.ltp);
        let highVal = num(snap.high);
        if (highVal === null) highVal = num(snap.ltp);
        let closeVal = num(snap.close);
        if (closeVal === null || closeVal === 0) closeVal = num(snap.ltp);

        if (prevVal != null) badgeTop.push(`<span class="logging-marker-badge"><span class="dot mk-prev"></span>Prev: ${formatPrice(prevVal)}</span>`);
        if (openVal != null) badgeTop.push(`<span class="logging-marker-badge"><span class="dot mk-open"></span>Open: ${formatPrice(openVal)}</span>`);
        if (closeVal != null) badgeTop.push(`<span class="logging-marker-badge"><span class="dot mk-close"></span>Close: ${formatPrice(closeVal)}</span>`);
        if (lowVal != null) badgeBottom.push(`<span class="logging-marker-badge"><span class="dot mk-low"></span>Low: ${formatPrice(lowVal)}</span>`);
        if (highVal != null) badgeBottom.push(`<span class="logging-marker-badge"><span class="dot mk-high"></span>High: ${formatPrice(highVal)}</span>`);
        badgeBottom.push(`<span class="logging-marker-badge"><span class="dot" style="background-color: #ffc107;"></span>LTP: ${formatPrice(ltpVal)}</span>`);

        const badgesHtml = ((badgeTop.length || badgeBottom.length)
            ? `<div class="logging-marker-row row-top">${badgeTop.join('')}</div>
               ${badgeBottom.length ? `<div class="logging-marker-row row-bottom">${badgeBottom.join('')}</div>` : ''}`
            : '');

        const t = ltpVal ?? 0;
        const prev = prevVal ?? 0;
        const hasTrendBasis = t > 0 && prev > 0;
        const isTrendUp = hasTrendBasis && (t > prev);
        const isTrendDown = hasTrendBasis && (t < prev);

        const prev1 = prevVal ?? 0;
        const prev2Raw = Number(item.t2Close);
        const prev2 = Number.isFinite(prev2Raw) && prev2Raw > 0 ? prev2Raw : 0;
        const hasReversalBasis = t > 0 && prev1 > 0 && prev2 > 0;
        const isAngleDown = hasReversalBasis && prev2 < prev1 && t < prev1;
        const isAngleUp = hasReversalBasis && prev2 > prev1 && t > prev1;

        const iconClass = 'btn btn-sm d-flex align-items-center justify-content-center p-0';
        const iconStyle = 'width:32px; height:32px; border-radius: 4px;';

        const neutralTrendTitle = !hasTrendBasis
            ? 'Trend: Neutral (missing LTP/Prev Close)'
            : 'Trend: Neutral (LTP = Prev Close)';
        let iconTrend = `<button class="${iconClass} btn-outline-secondary" style="${iconStyle} cursor: default;" title="${neutralTrendTitle}"><i class="bi bi-dash-lg fs-5"></i></button>`;
        if (isTrendUp) {
            iconTrend = `<button class="${iconClass} btn-outline-success" style="${iconStyle}" title="Trend: Uptrend (LTP > Prev Close)"><i class="bi bi-graph-up-arrow fs-5 fw-bold" style="-webkit-text-stroke: 0.5px;"></i></button>`;
        } else if (isTrendDown) {
            iconTrend = `<button class="${iconClass} btn-outline-danger" style="${iconStyle}" title="Trend: Downtrend (LTP < Prev Close)"><i class="bi bi-graph-down-arrow fs-5 fw-bold" style="-webkit-text-stroke: 0.5px;"></i></button>`;
        }

        const neutralReversalTitle = !hasReversalBasis
            ? 'Reversal: Neutral (missing Prev1/Prev2/LTP)'
            : 'Reversal: Neutral (no reversal)';
        let iconAngle = `<button class="${iconClass} btn-outline-secondary" style="${iconStyle} cursor: default;" title="${neutralReversalTitle}"><i class="bi bi-dash-lg"></i></button>`;
        if (isAngleDown) {
            iconAngle = `<button class="${iconClass} btn-outline-light" style="${iconStyle}" title="Reversal: Down (Prev2 < Prev1, LTP < Prev1)"><i class="bi bi-chevron-down fw-bold fs-6"></i></button>`;
        } else if (isAngleUp) {
            iconAngle = `<button class="${iconClass} btn-outline-light" style="${iconStyle}" title="Reversal: Up (Prev2 > Prev1, LTP > Prev1)"><i class="bi bi-chevron-up fw-bold fs-6"></i></button>`;
        }

        if (state.loggingLayout === 'grid') {
            card.innerHTML = `
                <div class="logging-head d-flex justify-content-between align-items-start mb-2 gap-2">
                    <div class="logging-head-left d-flex flex-column gap-0" style="min-width: 0;">
                        <div class="d-flex align-items-center gap-2">
                            <div class="logging-title mb-0 text-truncate" title="Token: ${token}" style="cursor: help;">${symbol}</div>
                            ${status}
                        </div>
                    </div>
                    <div class="logging-actions d-flex gap-2 flex-shrink-0 align-items-center">
                        ${iconTrend}
                        ${iconAngle}
                        <button class="${iconClass} ${item.active ? 'btn-outline-warning' : 'btn-outline-success'}" style="${iconStyle}" data-action="toggle-log" data-id="${item.id}" data-active="${item.active}" title="${item.active ? 'Logging: Active (Click to Pause)' : 'Logging: Paused (Click to Resume)'}"><i class="bi ${item.active ? 'bi-pause-fill' : 'bi-play-circle'}"></i></button>
                    </div>
                </div>
                ${badgesHtml}
                <div class="logging-pressure mt-3">
                    <div class="pressure-meta d-flex justify-content-between small text-white-50 mb-1">
                        <span>Buy Qty</span>
                        <span>Sell Qty</span>
                    </div>
                    <div class="pressure-bar">
                        <div class="pressure-buy" style="width:${buyPct}%"></div>
                        <div class="pressure-sell bg-danger" style="width:${sellPct}%"></div>
                    </div>
                    <div class="pressure-meta pressure-bottom d-flex justify-content-between fw-bold" style="font-size: 0.8rem;">
                        <span class="text-white">${buyPct.toFixed(2)}% (${formatInr(buyQty)})</span>
                        <span class="text-white">${sellPct.toFixed(2)}% (${formatInr(sellQty)})</span>
                    </div>
                </div>
                <div class="logging-ohlc mt-2 pt-2 border-top border-secondary border-opacity-10">
                    <span class="logging-marker-badge compact"><span class="dot mk-vol"></span>Vol: ${snap.volume != null ? formatInr(snap.volume) : '-'}</span>
                    <span class="logging-marker-badge compact"><span class="dot mk-ltq"></span>LTQ: ${snap.ltq != null ? formatInr(snap.ltq) : '-'}</span>
                    <span class="logging-info-plain ms-auto small text-muted" title="Added: ${added} | Last logged: ${logged}"><i class="bi bi-info-circle"></i></span>
                </div>
            `;
        } else {
            card.innerHTML = `
                <div class="logging-list-row px-2 py-1">
                    <div class="logging-list-name">
                        <div class="logging-title mb-0 text-truncate" title="Token: ${token}" style="cursor: help;">${symbol}</div>
                    </div>

                    <div class="logging-list-status">
                        ${status.replace('badge', 'badge fs-6').replace('font-size: 0.7rem', '').replace('style="', 'style="font-size: 0.8rem !important; ')}
                    </div>

                    <div class="logging-list-ohlc">
                        <span class="logging-marker-badge logging-list-badge ps-2 border-start border-secondary border-opacity-25"><span class="dot mk-prev"></span>Prev: ${formatPrice(prevVal)}</span>
                        <span class="logging-marker-badge logging-list-badge"><span class="dot mk-open"></span>Open: ${formatPrice(openVal)}</span>
                        <span class="logging-marker-badge logging-list-badge"><span class="dot mk-close"></span>Close: ${formatPrice(closeVal)}</span>
                        <span class="logging-marker-badge logging-list-badge"><span class="dot mk-low"></span>Low: ${formatPrice(lowVal)}</span>
                        <span class="logging-marker-badge logging-list-badge"><span class="dot mk-high"></span>High: ${formatPrice(highVal)}</span>
                        <span class="logging-marker-badge logging-list-badge"><span class="dot" style="background-color: #ffc107;"></span>LTP: ${formatPrice(ltpVal)}</span>
                    </div>

                    <div class="logging-list-volume border-start border-secondary border-opacity-10">
                         <span class="logging-marker-badge logging-list-badge"><span class="dot mk-vol"></span>Vol: ${snap.volume != null ? formatInr(snap.volume) : '-'}</span>
                         <span class="logging-marker-badge logging-list-badge"><span class="dot mk-ltq"></span>LTQ: ${snap.ltq != null ? formatInr(snap.ltq) : '-'}</span>
                    </div>

                    <div class="logging-list-pressure border-start border-secondary border-opacity-10">
                        <div class="d-flex justify-content-between text-white-50 mb-0" style="font-size: 0.62rem;">
                            <span>Buy Qty</span>
                            <span>Sell Qty</span>
                        </div>
                        <div class="pressure-bar" style="height: 6px;">
                            <div class="pressure-buy" style="width: ${buyPct}%"></div>
                            <div class="pressure-sell bg-danger" style="width: ${sellPct}%"></div>
                        </div>
                        <div class="d-flex justify-content-between mt-0 fw-bold" style="font-size: 0.68rem;">
                            <span class="text-white" title="Buy: ${formatInr(buyQty)}">${buyPct.toFixed(0)}%</span>
                            <span class="text-white" title="Sell: ${formatInr(sellQty)}">${sellPct.toFixed(0)}%</span>
                        </div>
                    </div>

                    <div class="logging-list-actions">
                        ${iconTrend}
                        ${iconAngle}
                        <button class="${iconClass} ${item.active ? 'btn-outline-warning' : 'btn-outline-success'}" style="${iconStyle}" data-action="toggle-log" data-id="${item.id}" data-active="${item.active}" title="${item.active ? 'Logging: Active (Click to Pause)' : 'Logging: Paused (Click to Resume)'}"><i class="bi ${item.active ? 'bi-pause-fill' : 'bi-play-circle'}"></i></button>
                    </div>
                </div>
            `;
        }
        list.appendChild(card);
    });
}

export function setupLoggingTab() {
    const form = document.getElementById('logging-search-form');
    if (form) {
        form.addEventListener('submit', (ev) => {
            ev.preventDefault();
            if (!form.checkValidity()) {
                form.classList.add('was-validated');
                return;
            }
            const query = document.getElementById('logging-search-input')?.value || '';
            searchLoggingScrips(query);
        });
    }
    const searchInput = document.getElementById('logging-search-input');
    if (searchInput) {
        searchInput.addEventListener('keyup', (ev) => {
            if (ev.key === 'Enter') return;
            if (searchInput.value.trim().length >= 3) {
                searchLoggingScrips(searchInput.value);
            }
        });
    }
    const searchResults = document.getElementById('logging-search-results');
    if (searchResults) {
        searchResults.addEventListener('click', (ev) => {
            const btn = ev.target.closest('[data-action="add-logging"]');
            if (!btn) return;
            addLoggingScrip({
                tradingsymbol: btn.dataset.symbol,
                exchange: btn.dataset.exchange || 'NSE',
                instrumentToken: btn.dataset.token
            });
        });
    }

    const refreshBtn = document.getElementById('logging-refresh');
    if (refreshBtn) {
        refreshBtn.addEventListener('click', async () => {
            await fetchLoggingScrips();
            showToast('Logging tab refreshed');
        });
    }

    const list = document.getElementById('logging-scrips-list');
    if (list) {
        list.addEventListener('click', (ev) => {
            const btn = ev.target.closest('[data-action="toggle-log"]');
            if (btn) {
                const id = btn.dataset.id;
                const active = btn.dataset.active;
                toggleLoggingScrip(id, active);
            }
        });
    }

    const layoutList = document.getElementById('logging-layout-list');
    const layoutGrid = document.getElementById('logging-layout-grid');
    const setLayout = (mode) => {
        state.loggingLayout = mode === 'grid' ? 'grid' : 'list';
        renderLoggingScrips();
    };
    if (layoutList) layoutList.addEventListener('change', () => setLayout('list'));
    if (layoutGrid) layoutGrid.addEventListener('change', () => setLayout('grid'));

    const refreshUnresolvedBtn = document.getElementById('refresh-unresolved-btn');
    if (refreshUnresolvedBtn) {
        refreshUnresolvedBtn.addEventListener('click', fetchUnresolvedTokens);
    }
}

export async function fetchUnresolvedTokens() {
    const list = document.getElementById('unresolved-list');
    if (!list) return;
    try {
        const tokens = await fetchJson('/api/logging/unresolved');
        renderUnresolvedTokens(Array.from(tokens || []));
    } catch (e) {
        console.error('Failed to fetch unresolved tokens', e);
    }
}

function renderUnresolvedTokens(tokens) {
    const list = document.getElementById('unresolved-list');
    if (!list) return;

    const card = list.closest('.card');

    if (!tokens || tokens.length === 0) {
        if (card) card.classList.add('d-none');
        list.innerHTML = '';
        return;
    }

    if (card) card.classList.remove('d-none');
    list.innerHTML = '';

    tokens.forEach(token => {
        const item = document.createElement('div');
        item.className = 'list-group-item bg-dark text-white border-secondary d-flex justify-content-between align-items-center p-2';
        item.innerHTML = `
                <div>
                <span class="text-danger fw-bold small">Token ${token}</span>
            </div>
                <button class="btn btn-sm btn-outline-light py-0" style="font-size:0.75rem" onclick="navigator.clipboard.writeText('${token}')">Copy</button>
            `;
        list.appendChild(item);
    });
}
