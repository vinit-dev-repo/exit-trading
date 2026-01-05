export function showToast(message) {
    try {
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
    } catch (_) { console.log(message); }
}

export function setTooltip(el, title) {
    if (!el) return;
    el.setAttribute('title', title);
    try {
        if (window.bootstrap && bootstrap.Tooltip) {
            const inst = bootstrap.Tooltip.getInstance(el);
            if (inst) inst.dispose();
            new bootstrap.Tooltip(el);
        }
    } catch (_) { }
}

export function enableTooltips() {
    try {
        if (window.bootstrap && bootstrap.Tooltip) {
            document.querySelectorAll('[data-bs-toggle="tooltip"]').forEach(el => new bootstrap.Tooltip(el));
        }
    } catch (_) { }
}
