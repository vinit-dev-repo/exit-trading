import { fetchJson } from '../api.js';
import { state, STATUS } from './state.js';

export async function refreshSchedules() {
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
            ? `<span class="badge bg-secondary">Limit: ${schedule.limitPrice}</span>`
            : '<span class="badge bg-secondary">Market</span>';
        const auto = schedule.autoRepeat ? '<span class="badge bg-warning text-dark">Auto Repeat</span>' : '';
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
            ? `<span class="badge bg-dark">Limit: ${schedule.limitPrice}</span>`
            : '<span class="badge bg-dark">Market</span>';
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

export function showScheduleErrorModal(message) {
    try {
        const text = document.getElementById('schedule-error-text');
        if (text) text.textContent = message;
        const modalEl = document.getElementById('schedule-error-modal');
        if (!modalEl || !window.bootstrap || !bootstrap.Modal) { alert(message); return; }
        const modal = new bootstrap.Modal(modalEl);
        modal.show();
        const btn = document.getElementById('schedule-error-manual-btn');
        if (btn) {
            btn.onclick = () => {
                try {
                    const toggle = document.getElementById('manual-mode-toggle');
                    const input = document.getElementById('manualTime');
                    if (toggle && input) {
                        toggle.checked = true;
                        input.disabled = false;
                        if (!input.value) {
                            const now = new Date();
                            now.setMinutes(now.getMinutes() + 2);
                            input.value = now.toTimeString().slice(0, 5);
                        }
                        input.focus();
                    }
                } catch (_) { }
            };
        }
    } catch (_) { alert(message); }
}
