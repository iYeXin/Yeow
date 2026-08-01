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
  function _getCurrentCbStack(): string | null;

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

  /** @deprecated 兼容保留——优先使用 ES2026 原生 `Uint8Array.prototype.toBase64()` */
  function uint8ArrayToBase64(buffer: ArrayBuffer): string;
  /** @deprecated 兼容保留——优先使用 ES2026 原生 `Uint8Array.fromBase64()` */
  function base64ToUint8Array(base64: string): ArrayBuffer;
}

// yeow-dev：构建期虚拟模块（由 Yeow 构建器按 importer 所属依赖项注入命名空间）。
// 插件未安装 yeow-dev 时此声明生效；类型与实际构建行为一致。
declare module 'yeow-dev' {
  export function getAssetsPath(path: string): string;
}

export {};
