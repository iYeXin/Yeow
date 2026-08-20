// Runtime globals injected by init.js / yeow-api
declare global {
  var $dev: boolean | undefined;
  var $send: (channel: string, payload: Record<string, unknown>) => unknown | null;
  var $hm: (json: string) => string | null;

  function _registerCallback(
    fn: (...args: any[]) => any,
    options?: { persistent?: boolean }
  ): string;
  function _unregisterCallback(id: string): void;
  function _getCurrentCbStack(): { stack: string; outer: unknown } | null;
  function _attachCbStack(err: unknown): void;
  function reportError(e: unknown): void;

  // ── init.js 注入的标准环境（lib 为 ESNext，不含 DOM——自行声明）──
  interface YeowConsole {
    log(...args: any[]): void;
    info(...args: any[]): void;
    warn(...args: any[]): void;
    error(...args: any[]): void;
  }
  var console: YeowConsole;

  interface YeowResponse {
    ok: boolean;
    status: number;
    statusText: string;
    headers: { get(name: string): string | undefined };
    text(): Promise<string>;
    json(): Promise<any>;
    base64(): Promise<string>;
    bytes(): Promise<Uint8Array>;
    arrayBuffer(): Promise<ArrayBuffer>;
  }
  function fetch(
    url: string,
    init?: { method?: string; headers?: Record<string, string>; body?: string | Uint8Array | null; encoding?: 'utf8' | 'base64'; timeout?: number }
  ): Promise<YeowResponse>;

  // ── TextEncoder / TextDecoder（utf-8，polyfill 注入；同步 API）──
  interface TextEncoder {
    readonly encoding: string;
    encode(input?: string): Uint8Array;
  }
  const TextEncoder: {
    new (encoding?: string): TextEncoder;
    prototype: TextEncoder;
  };

  interface TextDecoder {
    readonly encoding: string;
    decode(input?: Uint8Array): string;
  }
  const TextDecoder: {
    new (encoding?: string): TextDecoder;
    prototype: TextDecoder;
  };

  function setTimeout(handler: (...args: any[]) => void, timeout?: number, ...args: any[]): string;
  function clearTimeout(id: string): void;
  function setInterval(handler: (...args: any[]) => void, timeout?: number, ...args: any[]): string;
  function clearInterval(id: string): void;

  var __plugin:
    | {
        name: string;
        version: string;
        author: string;
      }
    | undefined;

  var __yeowInitCbs: (() => void)[] | undefined;
  var __yeowLoadCbs: (() => void)[] | undefined;
  var __yeowUnloadCbs: (() => void)[] | undefined;
  var __yeowEventHandlers:
    | Record<
        string,
        { cbId: string; handler: Function; manualRelease: boolean }[]
      >
    | undefined;
}

export {};

// yeow-dev 备选声明见同目录 yeow-dev.d.ts（ambient，非模块文件）——
// 插件未安装 yeow-dev（构建期虚拟模块）时生效；已安装时以 yeow-dev 包内类型为准。
