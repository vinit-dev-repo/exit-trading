import { fetchJson } from '../api.js';
import { formatIst } from '../formatting.js';
import { state } from './state.js';
import { showToast } from './ui.js';

export async function fetchSessionStatus() {
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
            badge.innerHTML = `<i class="bi bi-check-circle me-1"></i>Active ${userLabel ? userLabel + ' ' : ''}- Expires ${expiryIst}`;
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
            badge.innerHTML = '<i class="bi bi-slash-circle me-1"></i>No active session';
            if (etaEl) etaEl.textContent = '';
        }

        const restoreBtn = document.getElementById('restore-session-btn');
        if (restoreBtn) {
            restoreBtn.addEventListener('click', async () => {
                try {
                    const res = await fetchJson('/api/admin/session/restore', { method: 'POST' });
                    showToast(`Session restored for ${res.user}`);
                    await fetchSessionStatus();
                } catch (err) {
                    showToast(err.message || 'Failed to restore session');
                }
            });
        }

        const loginBtn = document.getElementById('kite-login-btn');

        if (loginBtn) {
            if (!status.active || isExpired) {
                loginBtn.classList.remove('disabled');
                loginBtn.removeAttribute('aria-disabled');
                loginBtn.style.pointerEvents = 'auto';

                if (restoreBtn) {
                    restoreBtn.style.display = 'block';
                    restoreBtn.disabled = false;
                }
            } else {
                loginBtn.classList.add('disabled');
                loginBtn.setAttribute('aria-disabled', 'true');

                if (restoreBtn) {
                    restoreBtn.style.display = 'none';
                }
            }
        }
    } catch (err) {
        document.getElementById('session-status').textContent = 'Session status unavailable';
        const eta = document.getElementById('session-eta'); if (eta) eta.textContent = '';
    }
}
