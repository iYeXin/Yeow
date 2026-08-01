export function logError(err: any, context?: string): void {
    if (typeof (globalThis as any).$send !== 'function') return;
    const info: any = {
        message: err?.message || String(err),
        stack: err?.stack || '',
        fileName: err?.fileName || 'main.js',
        lineNumber: err?.lineNumber || 0,
        columnNumber: err?.columnNumber || 0,
    };
    if (context) info.context = context;
    if (!info.fileName || info.fileName === 'main.js' || !info.lineNumber) {
        const m = err?.stack?.match(/at\s+(?:\S+\s+)?\(?([^\s(]+):(\d+):(\d+)\)?/);
        if (m) { info.fileName = m[1]; info.lineNumber = parseInt(m[2]); info.columnNumber = parseInt(m[3]); }
    }
    (globalThis as any).$send('debug', { t: 'reportError', p: info });
}
