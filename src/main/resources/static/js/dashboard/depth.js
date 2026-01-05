import { fetchJson } from '../api.js';
import { formatPressure, formatInr, formatIst } from '../formatting.js';
import { state } from './state.js';
import { normalizeSymbol } from './utils.js';

export async function refreshDepth() {
    if (!state.currentUser) return;
    let depths;
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
    try {
        renderDepth(depths);
    } catch (err) {
        console.warn('Depth render failed', err);
        const panels = document.getElementById('depth-panels');
        if (panels && !panels.innerHTML) {
            panels.innerHTML = '<div class="et-depth-muted">Unable to render depth. Click Retry.</div>';
        }
    }
    try { await updateHoldingsEsmFlags(); } catch (_) { }
}

function renderDepth(depths) {
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
    renderDepthPanels(depths);
}

function renderDepthPanels(depths) {
    const container = document.getElementById('depth-panels');
    if (!container) return;
    container.innerHTML = '';
    const user = state.users.find(u => u.username === state.currentUser);
    const holdings = (user?.holdings || []).map(h => (h || '').split('|')[0]).filter(Boolean);
    const filter = state.holdingsFilter;
    const filtered = holdings.filter(h => {
        const exch = h.includes(':') ? h.split(':')[0].toUpperCase() : null;
        return filter === 'ALL' || exch === filter;
    });
    if (filtered.length === 0) {
        container.innerHTML = '<div class="et-depth-muted">No holdings to show.</div>';
        return;
    }
    const bySymbol = new Map();
    (depths || []).forEach(d => bySymbol.set(normalizeSymbol(d.tradingsymbol), d));
    state.depthDataBySymbol = bySymbol;
    const cards = [];
    filtered.forEach(h => {
        const sym = normalizeSymbol(h);
        const entry = bySymbol.get(sym);
        const card = renderSafeDepthCard(sym, entry);
        container.appendChild(card);
        cards.push(card);
    });
    const prevPage = state.depthPage || 0;
    state.depthCards = cards;
    applyDepthPage(prevPage);
}

export async function updateHoldingsEsmFlags() {
    try {
        if (!state.currentUser) return;
        const chips = Array.from(document.querySelectorAll('#holdings-list .holding-chip'));
        if (!chips.length) return;
        const uname = encodeURIComponent(state.currentUser || '');
        const list = await fetchJson(`/api/auction/flags/${uname}`);
        const bySymbol = new Map();
        (list || []).forEach(item => {
            if (item && item.symbol) {
                bySymbol.set(normalizeSymbol(item.symbol), item);
            }
        });

        const fmt = (v) => (v == null || Number.isNaN(v)) ? '-' : Number(v).toFixed(2);
        const fmtSigned = (v) => (v == null || Number.isNaN(v)) ? '-' : `${Number(v) >= 0 ? '+' : ''}${Number(v).toFixed(2)}`;
        const yesNo = (v) => v ? 'Yes' : 'No';
        const reasonLabel = (code, active) => {
            if (!active) return 'No trigger';
            if (code === 'UC_BREAK_EXP') return 'Upper-circuit break expected';
            if (code === 'TREND_WEAK') return 'Weak trend + imbalance';
            return 'Signal active';
        };
        const tooltipAttrs = 'data-bs-toggle="tooltip" data-bs-placement="top" data-bs-custom-class="tooltip-professional" data-bs-trigger="hover"';
        const infoIcon = (text) => `<span class="esm-flag-info" ${tooltipAttrs} title="${text}"><i class="bi bi-info-circle"></i></span>`;
        const labelWithInfo = (label, tip) => `<span class="esm-flag-label">${label}${infoIcon(tip)}</span>`;

        for (const chip of chips) {
            const sym = chip.dataset.symbol;
            const container = chip.querySelector('.chip-impact');
            if (!container) continue;
            disposeTooltips(container);
            const info = bySymbol.get(normalizeSymbol(sym));
            if (!info) {
                container.innerHTML = '<span class="impact-badge impact-low">ESM flags unavailable</span>';
                continue;
            }
            const downsideActive = !!info.downsideGateActive;
            const ucLock = !!info.upperCircuitLock;
            const ucBreak = !!info.upperCircuitBreakExpected;
            const downsideCls = downsideActive ? 'impact-high' : 'impact-low';
            const lockCls = ucLock ? 'impact-mod' : 'impact-low';
            const breakCls = ucBreak ? 'impact-mod' : 'impact-low';
            const reason = reasonLabel(info.downsideGateReason, downsideActive);

            container.innerHTML = `
                <div class="chip-impact-head">
                  <span class="impact-legend">ESM Flags</span>
                  <span class="legend-chip ${downsideCls}">Downside gate: ${yesNo(downsideActive)}</span>
                </div>
                <table class="chip-impact-table">
                  <tr class="${downsideCls}"><td>${labelWithInfo('Downside gate', 'Downside expected when UC break is expected or weak trend/session drift with negative imbalance.')}</td><td>${yesNo(downsideActive)}</td><td>${labelWithInfo('Reason', 'Primary trigger that activated the downside gate.')}</td><td class="right">${reason}</td></tr>
                  <tr class="${lockCls}"><td>${labelWithInfo('Upper-circuit lock', 'LTP and robust equilibrium at upper circuit within a tick.')}</td><td>${yesNo(ucLock)}</td><td>${labelWithInfo('Upper-circuit break expected', 'LTP at UC, robust equilibrium below UC by at least a tick, weak trend/session drift, and negative imbalance.')}</td><td class="right">${yesNo(ucBreak)}</td></tr>
                  <tr><td>${labelWithInfo('Trend delta (tpm)', 'Change in median robust equilibrium between two 5-minute windows, in ticks per minute.')}</td><td>${fmtSigned(info.trendDeltaTpm)}</td><td>${labelWithInfo('Session drift', 'Median robust equilibrium current session minus previous session (fallback when snapshots are sparse).')}</td><td class="right">${fmtSigned(info.sessionDrift)}</td></tr>
                  <tr><td>${labelWithInfo('Equilibrium (robust)', 'Age-weighted equilibrium price from top 5 levels.')}</td><td>${fmt(info.equilibriumPriceRobust)}</td><td>${labelWithInfo('Equilibrium (raw)', 'Unweighted equilibrium price from top 5 levels.')}</td><td class="right">${fmt(info.equilibriumPriceRaw)}</td></tr>
                </table>`;
        }
        refreshTooltips(document.getElementById('holdings-list'));
    } catch (_) { }
}

function refreshTooltips(root) {
    if (!root) return;
    if (!window.bootstrap || !bootstrap.Tooltip) return;
    root.querySelectorAll('[data-bs-toggle="tooltip"]').forEach(el => {
        const inst = bootstrap.Tooltip.getInstance(el);
        if (inst) inst.dispose();
        new bootstrap.Tooltip(el, { trigger: 'hover' });
    });
}

function disposeTooltips(root) {
    if (!root) return;
    if (!window.bootstrap || !bootstrap.Tooltip) return;
    root.querySelectorAll('[data-bs-toggle="tooltip"]').forEach(el => {
        const inst = bootstrap.Tooltip.getInstance(el);
        if (inst) inst.dispose();
    });
}

function renderSafeDepthCard(symbol, entry) {
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
    for (let i = 0; i < rows; i++) {
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

export function applyDepthPage(newPage) {
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
}
