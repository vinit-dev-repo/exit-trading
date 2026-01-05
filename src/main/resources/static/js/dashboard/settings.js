import { fetchJson } from '../api.js';
import { showToast, enableTooltips } from './ui.js';

let settingsCache = [];
const settingsByKey = new Map();

function isSensitiveKey(key) {
    if (!key) return false;
    const lowered = key.toLowerCase();
    return lowered.includes('secret') || lowered.includes('password');
}

function normalizeNumber(value) {
    if (value == null) return '';
    const str = String(value).trim();
    if (str === '') return '';
    const n = Number(str);
    return Number.isFinite(n) ? String(n) : str;
}

function normalizeValue(value, type) {
    if (type === 'boolean') {
        if (typeof value === 'string') return value.trim().toLowerCase() === 'true';
        return Boolean(value);
    }
    if (type === 'number') return normalizeNumber(value);
    return value == null ? '' : String(value);
}

function formatValue(value) {
    if (value == null || value === '') return '-';
    return String(value);
}

function groupSettings(settings) {
    const grouped = new Map();
    (settings || []).forEach(item => {
        const group = item.group || 'Other';
        if (!grouped.has(group)) grouped.set(group, []);
        grouped.get(group).push(item);
    });
    return grouped;
}

function slugifyGroup(group) {
    if (!group) return 'other';
    return group.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '') || 'other';
}

function isRestartRequired(key) {
    if (!key) return false;
    return key.startsWith('server.')
        || key.startsWith('spring.')
        || key.startsWith('management.')
        || key.startsWith('logging.file.');
}

function isReloginRequired(key) {
    if (!key) return false;
    return key === 'kite.enabled' || key === 'kite.apiKey' || key === 'kite.apiSecret';
}

function buildSettingRow(item) {
    const row = document.createElement('div');
    row.className = 'settings-row';
    if (item.overridden) row.classList.add('is-overridden');
    row.dataset.key = item.key;
    row.dataset.group = item.group || 'Other';

    const meta = document.createElement('div');
    meta.className = 'settings-meta';

    const keyLine = document.createElement('div');
    keyLine.className = 'settings-key';
    keyLine.textContent = item.key;

    const info = document.createElement('span');
    info.className = 'settings-info';
    info.setAttribute('data-bs-toggle', 'tooltip');
    info.setAttribute('data-bs-placement', 'top');
    info.setAttribute('data-bs-custom-class', 'tooltip-professional');
    info.setAttribute('title', item.description || 'No description available.');
    info.innerHTML = '<i class="bi bi-info-circle"></i>';

    keyLine.appendChild(info);
    meta.appendChild(keyLine);

    const badgeLine = document.createElement('div');
    badgeLine.className = 'settings-badges';
    const defaultBadge = document.createElement('span');
    defaultBadge.className = 'badge bg-secondary';
    defaultBadge.textContent = `Default: ${formatValue(item.defaultValue)}`;
    badgeLine.appendChild(defaultBadge);
    if (isRestartRequired(item.key)) {
        const restartBadge = document.createElement('span');
        restartBadge.className = 'badge bg-danger';
        restartBadge.textContent = 'Restart required';
        badgeLine.appendChild(restartBadge);
    } else if (isReloginRequired(item.key)) {
        const reloginBadge = document.createElement('span');
        reloginBadge.className = 'badge bg-info text-dark';
        reloginBadge.textContent = 'Re-login required';
        badgeLine.appendChild(reloginBadge);
    } else {
        const liveBadge = document.createElement('span');
        liveBadge.className = 'badge bg-success';
        liveBadge.textContent = 'Live';
        badgeLine.appendChild(liveBadge);
    }
    if (item.overridden) {
        const overrideBadge = document.createElement('span');
        overrideBadge.className = 'badge bg-warning text-dark';
        overrideBadge.textContent = 'Overridden';
        badgeLine.appendChild(overrideBadge);
    }
    meta.appendChild(badgeLine);

    const inputWrap = document.createElement('div');
    inputWrap.className = 'settings-input';

    const type = item.type || 'string';
    let input;
    if (type === 'boolean') {
        const switchWrap = document.createElement('div');
        switchWrap.className = 'form-check form-switch';
        input = document.createElement('input');
        input.type = 'checkbox';
        input.className = 'form-check-input';
        input.checked = !!item.value;
        input.dataset.settingKey = item.key;
        input.dataset.settingType = type;
        switchWrap.appendChild(input);
        inputWrap.appendChild(switchWrap);
    } else {
        input = document.createElement('input');
        input.type = type === 'number' ? 'number' : (isSensitiveKey(item.key) ? 'password' : 'text');
        input.className = 'form-control form-control-sm';
        input.value = item.value == null ? '' : String(item.value);
        if (type === 'number') input.step = 'any';
        input.dataset.settingKey = item.key;
        input.dataset.settingType = type;
        inputWrap.appendChild(input);
    }

    row.appendChild(meta);
    row.appendChild(inputWrap);
    return row;
}

function renderGroupIndex(grouped) {
    const index = document.getElementById('settings-group-index');
    if (!index) return;
    index.innerHTML = '';
    if (!grouped || grouped.size === 0) {
        index.innerHTML = '<div class="text-muted small">No groups available.</div>';
        return;
    }
    grouped.forEach((items, group) => {
        const slug = slugifyGroup(group);
        const link = document.createElement('button');
        link.type = 'button';
        link.className = 'settings-index-item';
        link.dataset.groupSlug = slug;
        link.innerHTML = `<span>${group}</span><span class="badge bg-secondary">${items.length}</span>`;
        link.addEventListener('click', () => {
            const target = document.getElementById(`settings-group-${slug}`);
            if (target) target.scrollIntoView({ behavior: 'smooth', block: 'start' });
        });
        index.appendChild(link);
    });
}

function renderSettings(settings) {
    const container = document.getElementById('settings-container');
    if (!container) return;
    container.innerHTML = '';
    settingsByKey.clear();
    (settings || []).forEach(item => settingsByKey.set(item.key, item));

    if (!settings || settings.length === 0) {
        container.innerHTML = '<div class="text-muted">No settings available.</div>';
        return;
    }

    const grouped = groupSettings(settings);
    renderGroupIndex(grouped);
    grouped.forEach((items, group) => {
        const groupEl = document.createElement('div');
        groupEl.className = 'settings-group';
        groupEl.dataset.group = group;
        const slug = slugifyGroup(group);
        groupEl.dataset.groupSlug = slug;
        groupEl.id = `settings-group-${slug}`;

        const header = document.createElement('div');
        header.className = 'settings-group-header';
        header.textContent = group;
        groupEl.appendChild(header);

        const body = document.createElement('div');
        body.className = 'settings-group-body';
        items.forEach(item => body.appendChild(buildSettingRow(item)));
        groupEl.appendChild(body);
        container.appendChild(groupEl);
    });

    enableTooltips();
    const searchInput = document.getElementById('settings-search');
    if (searchInput && searchInput.value) {
        applyFilter(searchInput.value);
    }
}

async function loadSettings() {
    const resp = await fetchJson('/api/settings');
    settingsCache = resp?.settings || [];
    renderSettings(settingsCache);
}

function applyFilter(query) {
    const q = query.trim().toLowerCase();
    const rows = document.querySelectorAll('.settings-row');
    rows.forEach(row => {
        const key = row.dataset.key?.toLowerCase() || '';
        const group = row.dataset.group?.toLowerCase() || '';
        const match = !q || key.includes(q) || group.includes(q);
        row.classList.toggle('d-none', !match);
    });
    document.querySelectorAll('.settings-group').forEach(group => {
        const visible = group.querySelectorAll('.settings-row:not(.d-none)');
        const hidden = visible.length === 0;
        group.classList.toggle('d-none', hidden);
        const slug = group.dataset.groupSlug;
        const link = document.querySelector(`.settings-index-item[data-group-slug="${slug}"]`);
        if (link) link.classList.toggle('d-none', hidden);
    });
}

function collectUpdates() {
    const updates = {};
    const inputs = document.querySelectorAll('[data-setting-key]');
    inputs.forEach(input => {
        const key = input.dataset.settingKey;
        const type = input.dataset.settingType || 'string';
        const item = settingsByKey.get(key);
        let currentValue;
        if (type === 'boolean') {
            currentValue = input.checked;
        } else {
            currentValue = input.value != null ? input.value.trim() : '';
        }

        const normalizedCurrent = normalizeValue(currentValue, type);
        const normalizedOriginal = normalizeValue(item?.value, type);
        if (normalizedCurrent !== normalizedOriginal) {
            updates[key] = currentValue;
        }
    });
    return updates;
}

export function setupSettingsTab() {
    const saveBtn = document.getElementById('settings-save');
    const restoreBtn = document.getElementById('settings-restore');
    const refreshBtn = document.getElementById('settings-refresh');
    const searchInput = document.getElementById('settings-search');
    const settingsFab = document.getElementById('settings-fab');
    const settingsLink = document.getElementById('tab-settings-link');

    if (saveBtn) {
        saveBtn.addEventListener('click', async () => {
            const updates = collectUpdates();
            if (Object.keys(updates).length === 0) {
                showToast('No settings changes to save');
                return;
            }
            await fetchJson('/api/settings', {
                method: 'POST',
                body: JSON.stringify({ updates })
            });
            showToast('Settings saved');
            await loadSettings();
        });
    }

    if (restoreBtn) {
        restoreBtn.addEventListener('click', async () => {
            const confirmed = window.confirm('Restore all settings to defaults?');
            if (!confirmed) return;
            await fetchJson('/api/settings/restore', { method: 'POST' });
            showToast('Defaults restored');
            await loadSettings();
        });
    }

    if (refreshBtn) {
        refreshBtn.addEventListener('click', async () => {
            await loadSettings();
            showToast('Settings refreshed');
        });
    }

    if (searchInput) {
        searchInput.addEventListener('input', () => applyFilter(searchInput.value || ''));
    }

    if (settingsFab && settingsLink && window.bootstrap && bootstrap.Tab) {
        settingsFab.addEventListener('click', () => {
            const tab = new bootstrap.Tab(settingsLink);
            tab.show();
        });
    }

    loadSettings().catch(err => {
        console.error('Settings load failed', err);
        const container = document.getElementById('settings-container');
        if (container) {
            container.innerHTML = `<div class="text-muted">Unable to load settings: ${err?.message || err}</div>`;
        }
    });
}
