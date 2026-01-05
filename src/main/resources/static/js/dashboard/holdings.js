import { fetchJson } from '../api.js';
import { state } from './state.js';
import { normalizeSymbol } from './utils.js';
import { refreshDepth } from './depth.js';

function parseHolding(entry) {
    if (!entry) return { exchange: null, symbol: '' };
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

export function renderHoldings(holdings) {
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
        ].filter(Boolean).join(' ');
        chip.innerHTML = `
            <div class="chip-line"><strong>${symbol}</strong>${exchange ? ` <span class="text-muted">(${exchange})</span>` : ''} </strong>${product ? ` <span class="text-muted">${product}</span>` : ''} </div>
            <div class="chip-meta text-muted">${[
                qty != null ? `Qty: ${qty}` : null,
                avg != null ? `Avg: ${avg.toFixed(2)}` : null,
                last != null ? `LTP: ${last.toFixed(2)}` : null,
                pct != null ? `Day(%): ${pct.toFixed(2)}` : null,
                pnl != null ? `P&L: ${Number(pnl).toFixed(2)}` : null,
            ].filter(Boolean).join(' ')
            }</div>`;

        try {
            const meta = chip.querySelector('.chip-meta');
            const row1 = [
                (qty != null ? `<span class="chip-flag">Qty: ${qty}</span>` : null),
                (avg != null ? `<span class="chip-flag">Avg: ${avg.toFixed(2)}</span>` : null),
                (last != null ? `<span class="chip-flag">LTP: ${last.toFixed(2)}</span>` : null),
            ].filter(Boolean).join(' ');
            const row2 = [
                (pct != null ? `<span class="chip-flag">Day(%): ${pct.toFixed(2)}</span>` : null),
                (pnl != null ? `<span class="chip-flag">P&L: ${Number(pnl).toFixed(2)}</span>` : null),
            ].filter(Boolean).join(' ');
            if (meta) {
                meta.classList.remove('text-muted');
                meta.innerHTML = `${row1 ? `<div class="chip-meta-row">${row1}</div>` : ''}${row2 ? `<div class="chip-meta-row">${row2}</div>` : ''}`;
            }
        } catch (_) { }
        try {
            const impact = document.createElement('div');
            impact.className = 'chip-impact';
            impact.innerHTML = '<span class="impact-badge impact-low">ESM flags pending</span>';
            chip.appendChild(impact);

        } catch (_) { }
        chip.addEventListener('click', () => presetSchedule({ symbol, token, qty }));
        container.appendChild(chip);
        try {
            const hr = document.createElement('hr');
            hr.className = 'border-dark';
            container.appendChild(hr);
        } catch (_) { }
    });
}

export async function refreshAuctionSummary() {
    if (!state.currentUser) return;
    try {
        const uname = encodeURIComponent(state.currentUser || '');
        const list = await fetchJson(`/api/auction/summary/${uname}`);
        const map = new Map();
        (list || []).forEach(d => {
            if (d && d.tradingsymbol) { map.set(normalizeSymbol(d.tradingsymbol), d); }
        });
        state.analysisBySymbol = map;
    } catch (err) {
        state.analysisBySymbol = new Map();
    }
}

function presetSchedule(holding) {
    const symbol = typeof holding === 'string' ? holding : holding.symbol;
    const token = typeof holding === 'string' ? null : holding.token;
    const qty = typeof holding === 'string' ? null : holding.qty;

    try {
        const triggerEl = document.querySelector('#tab-dashboard-link');
        if (triggerEl && window.bootstrap && bootstrap.Tab) {
            const tab = bootstrap.Tab.getItem(triggerEl) || new bootstrap.Tab(triggerEl);
            tab.show();
        } else if (triggerEl) {
            triggerEl.click();
        }
    } catch (_) { }

    document.getElementById('tradingsymbol').value = symbol;
    if (token) document.getElementById('instrumentToken').value = token;
    if (qty != null && !Number.isNaN(qty)) document.getElementById('quantity').value = qty;
    setTimeout(() => document.getElementById('instrumentToken').focus(), 300);
    state.selectedSymbol = symbol;
    refreshDepth();
}
