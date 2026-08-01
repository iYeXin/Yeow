export function join(...segments: (string | null | undefined)[]): string {
    return segments.filter(s => s != null).join('/').replace(/\/+/g, '/').replace(/\/$/, '') || '.';
}

export function basename(p: string): string {
    const s = p.replace(/\/+$/, '');
    const i = s.lastIndexOf('/');
    return i === -1 ? s : s.substring(i + 1);
}

export function dirname(p: string): string {
    const s = p.replace(/\/+$/, '');
    const i = s.lastIndexOf('/');
    return i === -1 ? '.' : s.substring(0, i) || '/';
}

export function extname(p: string): string {
    const b = basename(p);
    const i = b.lastIndexOf('.');
    return i === -1 ? '' : b.substring(i);
}

export const path = { join, basename, dirname, extname };
