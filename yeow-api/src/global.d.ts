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
  }
  function fetch(
    url: string,
    init?: { method?: string; headers?: Record<string, string>; body?: string | null }
  ): Promise<YeowResponse>;

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

// yeow-dev：构建期虚拟模块（由 Yeow 构建器按 importer 所属依赖项注入命名空间）。
// 插件未安装 yeow-dev 时此声明生效；类型与实际构建行为一致。
declare module 'yeow-dev' {
  export function getAssetsPath(path: string): string;
}

export {};
