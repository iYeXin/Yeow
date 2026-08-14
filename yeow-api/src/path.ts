export function join(...segments: (string | null | undefined)[]): string {
    return segments.filter(s => s != null).join('/').replace(/\/+/g, '/').replace(/\/$/, '') || '.';
}

export function basename(p: string): string {
    // 双分隔符：兼容 Windows 反斜杠路径（fs 通道在 Windows 上返回 `\` 路径）
    const s = p.replace(/[\\/]+$/, '');
    const i = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
    return i === -1 ? s : s.substring(i + 1);
}

export function dirname(p: string): string {
    const s = p.replace(/[\\/]+$/, '');
    const i = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
    if (i === -1) return '.';
    const d = s.substring(0, i);
    // 根：POSIX `/`；Windows 盘符根（`C:\` → `C:\`，`C:\a` → `C:` 兼容）
    if (d === '') return s.length >= 3 && s[1] === ':' ? s.substring(0, 3) : '/';
    return d;
}

export function extname(p: string): string {
    const b = basename(p);
    const i = b.lastIndexOf('.');
    return i === -1 ? '' : b.substring(i);
}

export const path = { join, basename, dirname, extname };
