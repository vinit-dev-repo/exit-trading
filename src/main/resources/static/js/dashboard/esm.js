import { fetchJson } from '../api.js';
import { fetchLoggingScrips, fetchUnresolvedTokens } from './logging.js';

export function setupEsmScreener() {
    const esmForm = document.getElementById('esm-screener-form');
    if (esmForm) {
        esmForm.addEventListener('submit', async (e) => {
            e.preventDefault();
            const btn = document.getElementById('esm-process-btn');
            const statusEl = document.getElementById('esm-status');
            const bseFile = document.getElementById('esm-bse-file').files[0];
            const nseFile = document.getElementById('esm-nse-file').files[0];
            const reportFile = document.getElementById('esm-report-file').files[0];
            const reviewPanel = document.getElementById('esm-review-panel');

            if (!reportFile && (!bseFile && !nseFile)) {
                statusEl.innerHTML = '<span class="text-danger">Please upload BSE/NSE files OR a Final Report.</span>';
                return;
            }

            btn.disabled = true;
            btn.innerHTML = '<span class="spinner-border spinner-border-sm me-2"></span>Processing...';
            statusEl.innerHTML = '<span class="text-info">Processing... Check logs for progress.</span>';
            reviewPanel.classList.add('d-none');

            let pollInterval;
            if (!reportFile) {
                pollInterval = setInterval(async () => {
                    try {
                        const s = await fetchJson('/api/esm/status');
                        if (s && s.message) {
                            statusEl.innerHTML = `<span class="text-secondary">${s.message} (Processed ${s.processed}/${s.total})</span>`;
                        }
                    } catch (_) { }
                }, 1000);
            }

            const formData = new FormData();
            if (bseFile) formData.append('bseFile', bseFile);
            if (nseFile) formData.append('nseFile', nseFile);
            if (reportFile) formData.append('reportFile', reportFile);

            try {
                const result = await fetchJson('/api/esm/process', {
                    method: 'POST',
                    body: formData,
                    headers: {}
                });

                if (pollInterval) clearInterval(pollInterval);

                const analysis = result.analysis || [];
                statusEl.innerHTML = `<span class="text-success fw-bold">${result.message || 'Analysis Complete'}</span>`;

                if (analysis.length > 0) {
                    reviewPanel.classList.remove('d-none');
                    const tbody = document.getElementById('esm-review-table-body');
                    tbody.innerHTML = '';

                    analysis.forEach(item => {
                        const tr = document.createElement('tr');
                        const isUnknown = item.status === 'UNKNOWN';
                        const isExisting = item.status === 'EXISTING';

                        let badgeClass = 'bg-success';
                        if (isUnknown) badgeClass = 'bg-danger';
                        else if (isExisting) badgeClass = 'bg-info text-dark';

                        tr.innerHTML = `
                            <td>${item.originalSymbol}</td>
                            <td><span class="badge ${badgeClass}">${item.status}</span></td>
                            <td>
                                ${isUnknown
                            ? `<input type="text" class="form-control form-control-sm bg-dark text-white border-secondary esm-correction-input" 
                                        data-original="${item.originalSymbol}" 
                                        placeholder="e.g. NSE:${item.originalSymbol}" 
                                        value="NSE:${item.originalSymbol}">`
                            : `<span class="text-muted">${item.suggestedSymbol}</span>`}
                            </td>
            `;
                        tbody.appendChild(tr);
                    });

                    const confirmBtn = document.getElementById('esm-confirm-btn');
                    confirmBtn.onclick = async () => {
                        confirmBtn.disabled = true;
                        confirmBtn.textContent = 'Importing...';

                        const corrections = {};
                        document.querySelectorAll('.esm-correction-input').forEach(input => {
                            const val = input.value.trim();
                            if (val) {
                                corrections[input.dataset.original] = val;
                            }
                        });

                        try {
                            const res = await fetch('/api/esm/confirm', {
                                method: 'POST',
                                headers: { 'Content-Type': 'application/json' },
                                body: JSON.stringify({
                                    fileName: result.downloadPath,
                                    corrections: corrections
                                })
                            });

                            if (!res.ok) throw new Error(await res.text() || 'Import failed');

                            reviewPanel.classList.add('d-none');
                            statusEl.innerHTML += '<br><span class="text-success fw-bold">Import Confirmed!</span>';
                            fetchLoggingScrips();
                            fetchUnresolvedTokens();
                        } catch (err) {
                            console.error(err);
                            statusEl.innerHTML += `<br><span class="text-danger">Import Failed: ${err.message}</span>`;
                            confirmBtn.disabled = false;
                            confirmBtn.textContent = 'Retry Import';
                        }
                    };
                } else {
                    statusEl.innerHTML += '<br><span class="text-muted">No new scrips found.</span>';
                }

                attachEsmSearchSuggestions();

            } catch (err) {
                if (pollInterval) clearInterval(pollInterval);
                console.error(err);
                statusEl.innerHTML = `<span class="text-danger">Error: ${err.message}</span>`;
            } finally {
                btn.disabled = false;
                btn.innerHTML = '<i class="bi bi-gear-wide-connected me-1"></i>Process & Review';
            }
        });
    }
}

function attachEsmSearchSuggestions() {
    const inputs = document.querySelectorAll('.esm-correction-input');

    let dropdown = document.getElementById('esm-suggestions-dropdown');
    if (!dropdown) {
        dropdown = document.createElement('div');
        dropdown.id = 'esm-suggestions-dropdown';
        dropdown.className = 'list-group position-absolute shadow-lg';
        dropdown.style.display = 'none';
        dropdown.style.zIndex = '9999';
        dropdown.style.maxHeight = '200px';
        dropdown.style.overflowY = 'auto';
        dropdown.style.width = '300px';
        dropdown.style.fontSize = '0.85rem';
        document.body.appendChild(dropdown);

        document.addEventListener('click', (e) => {
            if (!dropdown.contains(e.target) && !e.target.classList.contains('esm-correction-input')) {
                dropdown.style.display = 'none';
            }
        });
    }

    const triggerSearch = async (input) => {
        let query = input.value.trim();
        query = query.replace(/^(NSE|BSE):/i, '');

        if (query.length < 2) {
            dropdown.style.display = 'none';
            return;
        }

        try {
            const results = await fetchJson(`/api/logging/search?q=${encodeURIComponent(query)}`);
            if (results && results.length > 0) {
                renderEsmSuggestions(results, input, dropdown);
            } else {
                dropdown.style.display = 'none';
            }
        } catch (_) { }
    };

    inputs.forEach(input => {
        input.addEventListener('keyup', async (e) => {
            if (e.key === 'Enter' || e.key === 'Escape') {
                dropdown.style.display = 'none';
                return;
            }
            triggerSearch(input);
        });

        input.addEventListener('click', () => triggerSearch(input));
        input.addEventListener('focus', () => triggerSearch(input));
    });
}

function renderEsmSuggestions(results, targetInput, dropdown) {
    dropdown.innerHTML = '';
    const rect = targetInput.getBoundingClientRect();
    const scrollTop = window.pageYOffset || document.documentElement.scrollTop;
    const scrollLeft = window.pageXOffset || document.documentElement.scrollLeft;

    dropdown.style.top = (rect.bottom + scrollTop + 2) + 'px';
    dropdown.style.left = (rect.left + scrollLeft) + 'px';
    dropdown.style.display = 'block';

    results.slice(0, 10).forEach(r => {
        const item = document.createElement('button');
        item.className = 'list-group-item list-group-item-action bg-dark text-white border-secondary p-2';
        item.type = 'button';
        item.innerHTML = `
                <div class="d-flex justify-content-between">
                <span class="fw-bold text-info">${r.exchange}:${r.symbol}</span>
                <span class="badge bg-secondary ms-2">Add</span>
            </div>
                <div class="text-muted small" style="font-size: 0.75rem;">
                    Token ${r.token}${r.name ? ' - ' + r.name : ''}
                </div>
            `;
        item.onclick = () => {
            targetInput.value = `${r.exchange}:${r.symbol} `;
            dropdown.style.display = 'none';
        };
        dropdown.appendChild(item);
    });
}
