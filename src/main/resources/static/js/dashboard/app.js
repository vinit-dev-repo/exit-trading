import { fetchJson } from '../api.js';
import { state } from './state.js';
import { fetchNews } from './news.js';
import { refreshSchedules, showScheduleErrorModal } from './schedules.js';
import { refreshDepth, applyDepthPage } from './depth.js';
import { renderHoldings, refreshAuctionSummary } from './holdings.js';
import { fetchLoggingScrips, setupLoggingTab, fetchUnresolvedTokens } from './logging.js';
import { setupEsmScreener } from './esm.js';
import { fetchSessionStatus } from './session.js';
import { showToast, enableTooltips } from './ui.js';
import { setupSettingsTab } from './settings.js';
import { setupScreenerTab } from './screener.js';

async function loadUsers() {
    const users = await fetchJson('/api/admin/users');
    state.users = users;
    const selector = document.getElementById('user-selector');
    selector.innerHTML = '';
    users.forEach((user) => {
        const option = document.createElement('option');
        option.value = user.username;
        option.textContent = `${user.displayName} (${user.username})`;
        selector.appendChild(option);
    });
    selector.addEventListener('change', () => {
        state.currentUser = selector.value;
        bootstrapUserSettings();
    });
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
    await fetchLoggingScrips();
    await fetchUnresolvedTokens();
    await fetchSessionStatus();
}

function scheduleAutoRefresh() {
    if (state.refreshHandle) clearInterval(state.refreshHandle);
    if (state.scheduleHandle) clearInterval(state.scheduleHandle);
    if (state.depthHandle) clearInterval(state.depthHandle);
    if (state.sessionHandle) clearInterval(state.sessionHandle);
    if (state.newsHandle) clearInterval(state.newsHandle);
    if (state.loggingHandle) clearInterval(state.loggingHandle);
    state.scheduleHandle = setInterval(refreshSchedules, 15000);
    state.depthHandle = setInterval(refreshDepth, 8000);
    state.sessionHandle = setInterval(fetchSessionStatus, 60000);
    state.newsHandle = setInterval(fetchNews, 300000);
    state.loggingHandle = setInterval(fetchLoggingScrips, 10000);
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
                const time = resp.rolledTimeIst || (resp.nextExecutionTime ? new Date(resp.nextExecutionTime).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : 'next slot');
                showToast(`Scheduled for next available slot at ${time}`);
            } else {
                showToast('Schedule created');
            }
            refreshSchedules();
        } catch (err) {
            showScheduleErrorModal(err?.message || 'Scheduled time is in the past. Please enable Manual Time and choose a future time.');
        }
    });

    const manualToggle = document.getElementById('manual-mode-toggle');
    const manualTimeInput = document.getElementById('manualTime');
    if (manualToggle && manualTimeInput) {
        manualToggle.addEventListener('change', () => {
            manualTimeInput.disabled = !manualToggle.checked;
            if (manualToggle.checked && !manualTimeInput.value) {
                const now = new Date();
                now.setMinutes(now.getMinutes() + 1);
                manualTimeInput.value = now.toTimeString().slice(0, 5);
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

    const shutdownBtn = document.getElementById('shutdown-btn');
    if (shutdownBtn) {
        shutdownBtn.addEventListener('click', async () => {
            const confirmed = window.confirm('Gracefully stop the application now?');
            if (!confirmed) return;
            shutdownBtn.disabled = true;
            try {
                await fetchJson('/api/admin/system/shutdown', { method: 'POST' });
                showToast('Shutdown requested. The app will stop shortly.');
            } catch (err) {
                shutdownBtn.disabled = false;
                showToast(err?.message || 'Shutdown failed');
            }
        });
    }

    const syncBtn = document.getElementById('sync-holdings-btn');
    const holdingsFilter = document.getElementById('holdings-exchange-filter');

    if (syncBtn) {
        syncBtn.addEventListener('click', async () => {
            try {
                const res = await fetchJson('/api/admin/session/holdings/sync', { method: 'POST' });
                await loadUsers();
                try {
                    const sess = await fetchJson('/api/admin/session/status');
                    if (sess?.user) {
                        const selector = document.getElementById('user-selector');
                        selector.value = sess.user;
                        state.currentUser = sess.user;
                        bootstrapUserSettings();
                    }
                } catch (_) { }
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
    ['retry-depth', 'retry-upcoming', 'retry-executed', 'retry-holdings'].forEach(id => {
        const el = document.getElementById(id);
        if (el) el.addEventListener('click', doRetry);
    });

    const prev = document.getElementById('depth-prev');
    const next = document.getElementById('depth-next');
    if (prev) prev.addEventListener('click', () => applyDepthPage(state.depthPage - 1));
    if (next) next.addEventListener('click', () => applyDepthPage(state.depthPage + 1));
}

export async function initDashboard() {
    fetchNews().catch(() => { });
    await loadUsers();
    setupFormHandlers();
    setupLoggingTab();
    setupEsmScreener();
    setupScreenerTab();
    setupSettingsTab();
    await refreshSchedules();
    await refreshDepth();
    await fetchSessionStatus();
    scheduleAutoRefresh();
    enableTooltips();
}
