import { state } from './state.js';
import { formatIst } from '../formatting.js';

export async function fetchNews() {
    try {
        const container = document.getElementById('news-panel');
        if (!container) return;
        const resp = await fetch('/api/news');
        if (!resp.ok) throw new Error(`News fetch failed ${resp.status}`);
        const data = await resp.json();
        const items = data?.data?.latest_news || [];
        renderNews(items);
    } catch (err) {
        const container = document.getElementById('news-panel');
        if (container) container.innerHTML = '<div class="text-muted small">News unavailable (will retry)...</div>';
        try { clearTimeout(state.newsRetry); } catch (_) { }
        state.newsRetry = setTimeout(fetchNews, 15000);
        console.warn('News fetch error (retrying)', err?.message || err);
    }
}

function renderNews(items) {
    const container = document.getElementById('news-panel');
    if (!container) return;
    if (!items || items.length === 0) {
        container.innerHTML = '<div class="text-muted small">No news.</div>';
        return;
    }
    const repeat = 2;
    const list = [];
    for (let r = 0; r < repeat; r++) {
        list.push(...items);
    }
    const rows = list.map(n => {
        const title = n?.news_object?.title || '-';
        const text = n?.news_object?.text || '';
        const sent = (n?.news_object?.overall_sentiment || '').toLowerCase();
        const sentBadge = sent === 'positive' ? 'bg-success' : (sent === 'negative' ? 'bg-danger' : 'bg-secondary');
        const ts = n?.publish_date ? formatIst(n.publish_date) : '-';
        const sym = n?.display_symbol || n?.sm_symbol || '';
        return `<div class="news-item">
            <div class="d-flex justify-content-between">
                <span class="fw-bold">${sym}</span>
                <span class="badge ${sentBadge}">${sent || 'neutral'}</span>
            </div>
            <div class="news-title">${title}</div>
            <div class="news-time text-muted small">${ts}</div>
            <div class="news-text small">${text}</div>
        </div>`;
    }).join('');
    const duration = Math.max(10, list.length * 1.5);
    container.innerHTML = `<div class="news-scroll" style="--news-duration:${duration}s">${rows}</div>`;
}
