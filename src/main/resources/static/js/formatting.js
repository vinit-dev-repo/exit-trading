export function formatPressure(buy, sell) {
    if (buy === 0 && sell === 0) return 'Neutral';
    const total = buy + sell;
    const buyPct = Math.round((buy / total) * 100);
    const sellPct = 100 - buyPct;
    return `Buy ${buyPct}% / Sell ${sellPct}%`;
}

export function formatInr(x) {
    if (x === null || x === undefined) return '';
    const n = Number(x);
    if (!Number.isFinite(n)) return String(x);
    return n.toLocaleString('en-IN');
}

export function formatIst(value) {
    if (value === null || value === undefined || value === '-') return '-';
    let d;
    try {
        if (typeof value === 'number') {
            d = new Date(value > 1e12 ? value : value * 1000);
        } else if (typeof value === 'string' && /^\d+$/.test(value.trim())) {
            const n = Number(value.trim());
            d = new Date(n > 1e12 ? n : n * 1000);
        } else {
            d = new Date(value);
        }
        if (isNaN(d)) return '-';
        const parts = new Intl.DateTimeFormat('en-GB', {
            timeZone: 'Asia/Kolkata',
            day: '2-digit', month: 'short', year: '2-digit',
            hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false
        }).formatToParts(d);
        let dd = '01', mon = 'JAN', yy = '00', hh = '00', mm = '00', ss = '00';
        for (const p of parts) {
            if (p.type === 'day') dd = p.value;
            else if (p.type === 'month') mon = p.value.toUpperCase();
            else if (p.type === 'year') yy = p.value;
            else if (p.type === 'hour') hh = p.value;
            else if (p.type === 'minute') mm = p.value;
            else if (p.type === 'second') ss = p.value;
        }
        return `${dd}-${mon}-${yy} ${hh}:${mm}:${ss}`;
    } catch { return '-'; }
}
