function _sendLog(level: string, prefix: string, ...args: unknown[]): void {
  const msg = (prefix || '') + args.map((v) => { try { return String(v); } catch { return '?'; } }).join(' ');
  $send('log', { level, message: msg });
}

function _pluginPrefix(): string {
  return (typeof __plugin !== 'undefined' && __plugin?.name)
    ? '[' + __plugin.name + '] ' : '';
}

export const log = {
  info(...args: unknown[]): void {
    _sendLog('INFO', _pluginPrefix(), ...args);
  },
  warn(...args: unknown[]): void {
    _sendLog('WARN', _pluginPrefix(), ...args);
  },
  error(...args: unknown[]): void {
    _sendLog('ERROR', _pluginPrefix(), ...args);
  },
};

export class Logger {
  private prefix: string;

  constructor(prefix?: string) {
    this.prefix = prefix || '';
  }

  info(...args: unknown[]): void {
    _sendLog('INFO', this.prefix, ...args);
  }
  warn(...args: unknown[]): void {
    _sendLog('WARN', this.prefix, ...args);
  }
  error(...args: unknown[]): void {
    _sendLog('ERROR', this.prefix, ...args);
  }
}
