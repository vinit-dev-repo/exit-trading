export function normalizeSymbol(sym) {
    if (!sym) return sym;
    const idx = sym.indexOf(':');
    return idx > -1 ? sym.substring(idx + 1) : sym;
}
