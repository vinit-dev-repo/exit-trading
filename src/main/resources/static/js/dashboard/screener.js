import { fetchJson } from '../api.js';
import { state } from './state.js';
import { showToast, enableTooltips } from './ui.js';

const PAGE_SIZE = 200;
const RAIL_STORAGE_KEY = 'screenerRailHidden';

let columnCatalog = [];
let columnByKey = new Map();
let selectedColumns = new Set();
let computedColumns = [];
let activePresetId = null;
let currentSort = { key: 'symbol', direction: 'asc' };
let currentReportDate = null;
let useDefaultColumns = true;
let loading = false;
let hasMore = true;
let totalCount = 0;
let offset = 0;
let observer;

const numberFormatter = new Intl.NumberFormat('en-IN', { maximumFractionDigits: 4 });

function formatValue(value) {
    if (value == null || value === '') return '-';
    if (typeof value === 'number' && Number.isFinite(value)) {
        return numberFormatter.format(value);
    }
    return String(value);
}

function slugify(input) {
    return (input || '').toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '') || 'calc';
}

function getCurrentUser() {
    return state.currentUser;
}

function getSelectedColumnsPayload() {
    if (useDefaultColumns) return null;
    if (selectedColumns.size === 0) return null;
    const ordered = Array.from(selectedColumns).filter(key => key !== 'symbol');
    ordered.unshift('symbol');
    return ordered;
}

async function loadColumns() {
    const resp = await fetchJson('/api/screener/columns');
    columnCatalog = resp?.columns || [];
    columnByKey = new Map(columnCatalog.map(col => [col.key, col]));
}

async function loadLatestReportDate() {
    const resp = await fetchJson('/api/screener/latest-report-date');
    currentReportDate = resp?.reportDate || null;
    const badge = document.getElementById('screener-report-date');
    if (badge) {
        badge.textContent = currentReportDate ? `Report: ${currentReportDate}` : 'Report: --';
    }
    const input = document.getElementById('screener-report-date-input');
    if (input) {
        input.value = currentReportDate || '';
    }
}

function renderColumnPicker() {
    const container = document.getElementById('screener-column-picker');
    if (!container) return;
    container.innerHTML = '';
    const grouped = {};
    columnCatalog.forEach(col => {
        if (!grouped[col.group]) grouped[col.group] = [];
        grouped[col.group].push(col);
    });
    Object.entries(grouped).forEach(([group, items]) => {
        const block = document.createElement('div');
        block.className = 'screener-column-group';
        const title = document.createElement('div');
        title.className = 'screener-column-group-title';
        title.textContent = group;
        block.appendChild(title);
        items.forEach(col => {
            const row = document.createElement('label');
            row.className = 'screener-column-item';
            const checkbox = document.createElement('input');
            checkbox.type = 'checkbox';
            checkbox.className = 'form-check-input';
            checkbox.checked = selectedColumns.has(col.key);
            checkbox.disabled = col.key === 'symbol';
            checkbox.addEventListener('change', () => {
                useDefaultColumns = false;
                if (checkbox.checked) {
                    selectedColumns.add(col.key);
                } else {
                    selectedColumns.delete(col.key);
                    selectedColumns.add('symbol');
                }
            });
            const text = document.createElement('span');
            text.textContent = col.label;
            row.appendChild(checkbox);
            row.appendChild(text);
            block.appendChild(row);
        });
        container.appendChild(block);
    });
}

function renderCustomColumns() {
    const list = document.getElementById('screener-custom-list');
    if (!list) return;
    list.innerHTML = '';
    if (!computedColumns.length) {
        list.innerHTML = '<div class="text-muted small">No custom columns yet.</div>';
        return;
    }
    computedColumns.forEach(col => {
        const item = document.createElement('div');
        item.className = 'screener-custom-item';
        item.innerHTML = `
            <div>
                <div class="fw-semibold">${col.label}</div>
                <div class="screener-custom-meta">${col.expression}</div>
            </div>
        `;
        const remove = document.createElement('button');
        remove.className = 'btn btn-sm btn-outline-danger';
        remove.textContent = 'Remove';
        remove.addEventListener('click', () => {
            computedColumns = computedColumns.filter(c => c.key !== col.key);
            renderCustomColumns();
        });
        item.appendChild(remove);
        list.appendChild(item);
    });
}

function buildFilterGroup(root, isRoot = false) {
    const group = document.createElement('div');
    group.className = 'screener-filter-group';
    group.dataset.type = 'group';

    const head = document.createElement('div');
    head.className = 'screener-filter-group-head';

    const opSelect = document.createElement('select');
    opSelect.className = 'form-select form-select-sm screener-group-op';
    opSelect.innerHTML = '<option value="AND">AND</option><option value="OR">OR</option>';
    head.appendChild(opSelect);

    const actions = document.createElement('div');
    actions.className = 'd-flex gap-2';

    const addCondition = document.createElement('button');
    addCondition.type = 'button';
    addCondition.className = 'btn btn-sm btn-outline-light';
    addCondition.textContent = '+ Condition';
    actions.appendChild(addCondition);

    const addGroup = document.createElement('button');
    addGroup.type = 'button';
    addGroup.className = 'btn btn-sm btn-outline-light';
    addGroup.textContent = '+ Group';
    actions.appendChild(addGroup);

    if (!isRoot) {
        const remove = document.createElement('button');
        remove.type = 'button';
        remove.className = 'btn btn-sm btn-outline-danger';
        remove.textContent = 'Remove';
        remove.addEventListener('click', () => group.remove());
        actions.appendChild(remove);
    }

    head.appendChild(actions);
    group.appendChild(head);

    const children = document.createElement('div');
    children.className = 'screener-filter-children';
    group.appendChild(children);

    addCondition.addEventListener('click', () => {
        children.appendChild(buildFilterRow());
    });
    addGroup.addEventListener('click', () => {
        children.appendChild(buildFilterGroup(children));
    });

    root.appendChild(group);
    return group;
}

function buildFilterRow() {
    const row = document.createElement('div');
    row.className = 'screener-filter-row';
    row.dataset.type = 'condition';

    const fieldSelect = document.createElement('select');
    fieldSelect.className = 'form-select form-select-sm screener-field-select';
    fieldSelect.innerHTML = '<option value="">Select column</option>';
    columnCatalog.forEach(col => {
        const opt = document.createElement('option');
        opt.value = col.key;
        opt.textContent = col.label;
        fieldSelect.appendChild(opt);
    });

    const opSelect = document.createElement('select');
    opSelect.className = 'form-select form-select-sm screener-operator-select';

    const valueInput = document.createElement('input');
    valueInput.className = 'form-control form-control-sm screener-value-input';

    const remove = document.createElement('button');
    remove.type = 'button';
    remove.className = 'btn btn-sm btn-outline-danger';
    remove.textContent = 'X';
    remove.addEventListener('click', () => row.remove());

    row.appendChild(fieldSelect);
    row.appendChild(opSelect);
    row.appendChild(valueInput);
    row.appendChild(remove);

    fieldSelect.addEventListener('change', () => updateOperatorOptions(fieldSelect, opSelect, valueInput));
    updateOperatorOptions(fieldSelect, opSelect, valueInput);

    return row;
}

function updateOperatorOptions(fieldSelect, opSelect, valueInput) {
    const key = fieldSelect.value;
    const meta = columnByKey.get(key);
    const type = meta?.type || 'string';
    const ops = (type === 'number' || type === 'date')
        ? ['=', '!=', '>', '>=', '<', '<=', 'between', 'in', 'isNull', 'isNotNull']
        : ['=', '!=', 'contains', 'startsWith', 'endsWith', 'in', 'isNull', 'isNotNull'];
    opSelect.innerHTML = '';
    ops.forEach(op => {
        const opt = document.createElement('option');
        opt.value = op;
        opt.textContent = op;
        opSelect.appendChild(opt);
    });
    opSelect.onchange = () => updateValuePlaceholder(opSelect.value, valueInput);
    updateValuePlaceholder(opSelect.value, valueInput);
}

function updateValuePlaceholder(op, valueInput) {
    if (op === 'between') {
        valueInput.placeholder = 'min,max';
        valueInput.disabled = false;
    } else if (op === 'in') {
        valueInput.placeholder = 'a,b,c';
        valueInput.disabled = false;
    } else if (op === 'isNull' || op === 'isNotNull') {
        valueInput.placeholder = '';
        valueInput.value = '';
        valueInput.disabled = true;
    } else {
        valueInput.placeholder = 'value';
        valueInput.disabled = false;
    }
}

function buildFilterTree(groupEl) {
    if (!groupEl) return null;
    const operator = groupEl.querySelector('.screener-group-op')?.value || 'AND';
    const children = [];
    const childContainer = groupEl.querySelector('.screener-filter-children');
    if (childContainer) {
        Array.from(childContainer.children).forEach(child => {
            if (child.classList.contains('screener-filter-group')) {
                const nested = buildFilterTree(child);
                if (nested) children.push(nested);
            } else if (child.classList.contains('screener-filter-row')) {
                const field = child.querySelector('.screener-field-select')?.value;
                const op = child.querySelector('.screener-operator-select')?.value;
                const value = child.querySelector('.screener-value-input')?.value;
                if (field) {
                    children.push({
                        type: 'condition',
                        field,
                        op,
                        value
                    });
                }
            }
        });
    }
    return { type: 'group', operator, children };
}

function applyFilterTree(root, filters) {
    root.innerHTML = '';
    const group = buildFilterGroup(root, true);
    applyFilterChildren(group, filters);
}

function applyFilterChildren(group, filters) {
    if (!group || !filters) return;
    const childrenContainer = group.querySelector('.screener-filter-children');
    group.querySelector('.screener-group-op').value = filters.operator || 'AND';
    (filters.children || []).forEach(child => {
        if (child.type === 'group') {
            const nested = buildFilterGroup(childrenContainer);
            applyFilterChildren(nested, child);
        } else if (child.type === 'condition') {
            const row = buildFilterRow();
            row.querySelector('.screener-field-select').value = child.field || '';
            updateOperatorOptions(row.querySelector('.screener-field-select'), row.querySelector('.screener-operator-select'), row.querySelector('.screener-value-input'));
            row.querySelector('.screener-operator-select').value = child.op || '=';
            updateValuePlaceholder(row.querySelector('.screener-operator-select').value, row.querySelector('.screener-value-input'));
            row.querySelector('.screener-value-input').value = child.value ?? '';
            childrenContainer.appendChild(row);
        }
    });
}

function renderPresets(presets) {
    const list = document.getElementById('screener-presets-list');
    if (!list) return;
    list.innerHTML = '';
    if (!presets.length) {
        list.innerHTML = '<div class="text-muted small">No presets saved.</div>';
        return;
    }
    presets.forEach(preset => {
        const item = document.createElement('div');
        item.className = 'screener-preset-item';
        if (activePresetId === preset.id) item.classList.add('active');
        item.innerHTML = `<span>${preset.name}</span>`;
        item.addEventListener('click', () => {
            activePresetId = preset.id;
            document.getElementById('screener-preset-name').value = preset.name;
            applyPresetConfig(preset.config);
            renderPresets(presets);
        });
        list.appendChild(item);
    });
}

async function loadPresets() {
    const user = getCurrentUser();
    if (!user) return;
    const presets = await fetchJson(`/api/screener/${encodeURIComponent(user)}/presets`);
    renderPresets(presets || []);
}

function applyPresetConfig(config) {
    const columns = config?.columns || [];
    selectedColumns = new Set(columns.length ? columns : []);
    selectedColumns.add('symbol');
    useDefaultColumns = !columns.length;
    computedColumns = Array.isArray(config?.computedColumns) ? config.computedColumns : [];
    currentSort = config?.sort || { key: 'symbol', direction: 'asc' };
    const dateInput = document.getElementById('screener-report-date-input');
    if (dateInput) {
        dateInput.value = config?.reportDate || currentReportDate || '';
    }
    renderColumnPicker();
    renderCustomColumns();
    const builder = document.getElementById('screener-filter-builder');
    if (builder) {
        applyFilterTree(builder, config?.filters);
    }
    runQuery(true);
}

function buildQueryPayload() {
    const dateInput = document.getElementById('screener-report-date-input');
    const reportDate = dateInput?.value || null;
    const builder = document.getElementById('screener-filter-builder');
    const rootGroup = builder?.querySelector('.screener-filter-group');
    const filters = rootGroup ? buildFilterTree(rootGroup) : null;
    return {
        reportDate,
        columns: getSelectedColumnsPayload(),
        computedColumns,
        filters,
        sort: currentSort,
        limit: PAGE_SIZE,
        offset
    };
}

async function runQuery(reset = false) {
    if (loading) return;
    if (reset) {
        offset = 0;
        hasMore = true;
        totalCount = 0;
        document.getElementById('screener-table-body').innerHTML = '';
    }
    if (!hasMore) return;
    const user = getCurrentUser();
    if (!user) return;
    loading = true;
    toggleLoading(true);
    try {
        const payload = buildQueryPayload();
        const resp = await fetchJson(`/api/screener/${encodeURIComponent(user)}/query`, {
            method: 'POST',
            body: JSON.stringify(payload)
        });
        if (!resp) return;
        if (useDefaultColumns && resp.columns) {
            selectedColumns = new Set(resp.columns.map(c => c.key));
            selectedColumns.add('symbol');
            useDefaultColumns = false;
            renderColumnPicker();
        }
        if (resp.computedColumns) {
            computedColumns = resp.computedColumns;
            renderCustomColumns();
        }
        renderTableHeader(resp.columns || [], resp.computedColumns || []);
        appendRows(resp.rows || [], resp.columns || [], resp.computedColumns || []);
        totalCount = resp.total || 0;
        hasMore = !!resp.hasMore;
        offset = resp.offset + resp.rows.length;
        updateMeta();
        updateReportBadge(resp.reportDate);
        const dateInput = document.getElementById('screener-report-date-input');
        if (dateInput && resp.reportDate) {
            dateInput.value = resp.reportDate;
        }
    } catch (err) {
        console.error(err);
        showToast(err?.message || 'Screener query failed');
    } finally {
        loading = false;
        toggleLoading(false);
    }
}

function updateReportBadge(reportDate) {
    const badge = document.getElementById('screener-report-date');
    if (badge) badge.textContent = reportDate ? `Report: ${reportDate}` : 'Report: --';
}

function updateMeta() {
    const countEl = document.getElementById('screener-results-count');
    if (countEl) {
        countEl.textContent = `${totalCount} results`;
    }
    const sortEl = document.getElementById('screener-sort-label');
    if (sortEl) {
        const meta = columnByKey.get(currentSort.key);
        if (meta) {
            sortEl.textContent = `Sorted by ${meta.label} (${currentSort.direction})`;
        } else {
            sortEl.textContent = '';
        }
    }
}

function renderTableHeader(columns, computed) {
    const head = document.getElementById('screener-table-head');
    if (!head) return;
    const tr = document.createElement('tr');
    const allCols = [...columns, ...computed.map(c => ({ key: c.key, label: c.label, sortable: false }))];
    allCols.forEach(col => {
        const th = document.createElement('th');
        th.textContent = col.label;
        if (col.sortable) {
            th.classList.add('sortable');
            const indicator = document.createElement('span');
            indicator.className = 'sort-indicator';
            if (currentSort.key === col.key) {
                indicator.textContent = currentSort.direction === 'desc' ? 'v' : '^';
            }
            th.appendChild(indicator);
            th.addEventListener('click', () => {
                if (currentSort.key === col.key) {
                    currentSort.direction = currentSort.direction === 'desc' ? 'asc' : 'desc';
                } else {
                    currentSort = { key: col.key, direction: 'asc' };
                }
                runQuery(true);
            });
        }
        tr.appendChild(th);
    });
    head.innerHTML = '';
    head.appendChild(tr);
}

function appendRows(rows, columns, computed) {
    const body = document.getElementById('screener-table-body');
    const empty = document.getElementById('screener-empty');
    if (!body) return;
    if (!rows.length && body.children.length === 0) {
        if (empty) empty.classList.remove('d-none');
        return;
    }
    if (empty) empty.classList.add('d-none');
    rows.forEach(row => {
        const tr = document.createElement('tr');
        columns.forEach(col => {
            const td = document.createElement('td');
            if (col.key === 'symbol') {
                const url = row.sourceUrl;
                if (url) {
                    const link = document.createElement('a');
                    link.href = url;
                    link.target = '_blank';
                    link.rel = 'noopener noreferrer';
                    link.textContent = row[col.key] || '-';
                    td.appendChild(link);
                } else {
                    td.textContent = row[col.key] || '-';
                }
            } else {
                td.textContent = formatValue(row[col.key]);
            }
            tr.appendChild(td);
        });
        computed.forEach(col => {
            const td = document.createElement('td');
            td.textContent = formatValue(row[col.key]);
            tr.appendChild(td);
        });
        body.appendChild(tr);
    });
}

function toggleLoading(show) {
    const el = document.getElementById('screener-loading');
    if (!el) return;
    el.classList.toggle('d-none', !show);
}

function setupLazyLoading() {
    const sentinel = document.getElementById('screener-sentinel');
    if (!sentinel) return;
    if (observer) observer.disconnect();
    observer = new IntersectionObserver(entries => {
        entries.forEach(entry => {
            if (entry.isIntersecting) {
                runQuery(false);
            }
        });
    }, { root: document.querySelector('.screener-table-wrap'), threshold: 0.1 });
    observer.observe(sentinel);
}

function setupRailToggle() {
    const tab = document.getElementById('tab-screener');
    const toggles = [
        document.getElementById('screener-rail-toggle'),
        document.getElementById('screener-rail-toggle-main')
    ].filter(Boolean);
    if (!tab || toggles.length === 0) return;
    const hidden = localStorage.getItem(RAIL_STORAGE_KEY) === 'true';
    tab.classList.toggle('screener-collapsed', hidden);
    toggles.forEach(toggle => {
        toggle.addEventListener('click', () => {
            tab.classList.toggle('screener-collapsed');
            localStorage.setItem(RAIL_STORAGE_KEY, tab.classList.contains('screener-collapsed'));
        });
    });
}

function setupActions() {
    const addCustom = document.getElementById('screener-add-custom');
    if (addCustom) {
        addCustom.addEventListener('click', () => {
            const labelEl = document.getElementById('screener-custom-label');
            const exprEl = document.getElementById('screener-custom-expression');
            const label = labelEl?.value.trim();
            const expression = exprEl?.value.trim();
            if (!label || !expression) {
                showToast('Provide a label and expression.');
                return;
            }
            const keyBase = slugify(label);
            const key = `calc_${keyBase}_${Date.now().toString().slice(-5)}`;
            computedColumns.push({ key, label, expression });
            if (labelEl) labelEl.value = '';
            if (exprEl) exprEl.value = '';
            renderCustomColumns();
        });
    }

    const runBtn = document.getElementById('screener-run');
    if (runBtn) runBtn.addEventListener('click', () => runQuery(true));

    const refreshBtn = document.getElementById('screener-refresh');
    if (refreshBtn) refreshBtn.addEventListener('click', () => runQuery(true));

    const saveBtn = document.getElementById('screener-save-preset');
    const saveAsBtn = document.getElementById('screener-save-as');
    const deleteBtn = document.getElementById('screener-delete-preset');

    if (saveBtn) {
        saveBtn.addEventListener('click', async () => {
            const name = document.getElementById('screener-preset-name')?.value.trim();
            if (!name) {
                showToast('Preset name required.');
                return;
            }
            const user = getCurrentUser();
            if (!user) return;
            const payload = { name, config: buildQueryPayload() };
            if (activePresetId) {
                await fetchJson(`/api/screener/${encodeURIComponent(user)}/presets/${activePresetId}`, {
                    method: 'PUT',
                    body: JSON.stringify(payload)
                });
                showToast('Preset updated.');
            } else {
                const resp = await fetchJson(`/api/screener/${encodeURIComponent(user)}/presets`, {
                    method: 'POST',
                    body: JSON.stringify(payload)
                });
                activePresetId = resp?.id || null;
                showToast('Preset saved.');
            }
            loadPresets();
        });
    }

    if (saveAsBtn) {
        saveAsBtn.addEventListener('click', async () => {
            const name = document.getElementById('screener-preset-name')?.value.trim();
            if (!name) {
                showToast('Preset name required.');
                return;
            }
            const user = getCurrentUser();
            if (!user) return;
            const payload = { name, config: buildQueryPayload() };
            const resp = await fetchJson(`/api/screener/${encodeURIComponent(user)}/presets`, {
                method: 'POST',
                body: JSON.stringify(payload)
            });
            activePresetId = resp?.id || null;
            showToast('Preset saved.');
            loadPresets();
        });
    }

    if (deleteBtn) {
        deleteBtn.addEventListener('click', async () => {
            if (!activePresetId) {
                showToast('Select a preset to delete.');
                return;
            }
            const user = getCurrentUser();
            if (!user) return;
            await fetchJson(`/api/screener/${encodeURIComponent(user)}/presets/${activePresetId}`, { method: 'DELETE' });
            activePresetId = null;
            document.getElementById('screener-preset-name').value = '';
            showToast('Preset deleted.');
            loadPresets();
        });
    }
}

export async function setupScreenerTab() {
    await loadColumns();
    selectedColumns.add('symbol');
    renderColumnPicker();
    renderCustomColumns();
    const builder = document.getElementById('screener-filter-builder');
    if (builder) {
        builder.innerHTML = '';
        buildFilterGroup(builder, true);
    }
    setupRailToggle();
    setupActions();
    setupLazyLoading();
    await loadLatestReportDate();
    await loadPresets();
    runQuery(true);

    const userSelector = document.getElementById('user-selector');
    if (userSelector) {
        userSelector.addEventListener('change', async () => {
            activePresetId = null;
            document.getElementById('screener-preset-name').value = '';
            await loadPresets();
            runQuery(true);
        });
    }

    enableTooltips();
}
