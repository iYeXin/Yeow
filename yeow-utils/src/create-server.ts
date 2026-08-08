import { listen, respond, close, fs, assetsReadBase64 } from 'yeow-api';
import type { RespondOptions } from 'yeow-api';

/** 响应体：字符串（文本）或完整响应选项（含 bodyBase64 二进制）。 */
export type ResponseBody = RespondOptions;

/** 路由 handler：返回字符串（文本响应）、响应选项对象（如 { bodyBase64, headers }），或不返回（继续下一层）。 */
export type RouteHandler = (
  req: RouteRequest,
) => string | ResponseBody | undefined | Promise<string | ResponseBody | undefined>;

/** 洋葱模型中间件：先执行前置逻辑，调用 next() 进入下一层（返回其响应），再执行后置逻辑。 */
export type Middleware = (
  req: RouteRequest,
  next: NextFn,
) => string | ResponseBody | undefined | Promise<string | ResponseBody | undefined>;

/** 调用链中下一层（调用它进入下一个中间件/路由；无下一层时返回 undefined）。 */
export type NextFn = () => Promise<string | ResponseBody | undefined>;

export interface RouteRequest {
  path: string;
  method: string;
  body: string;
  headers: Record<string, string>;
  /** 查询串（可能为 undefined——无查询参数时运行时返回 null）。使用前需容错。 */
  query: string | undefined;
  connId: string;
  serverId: string;
  params: Record<string, string>;
}

export interface Server {
  port: number | undefined;
  /** 注册通用中间件（洋葱模型）：按注册顺序执行，`next()` 进入下一层。 */
  use(mw: Middleware): Server;
  get(path: string, handler: RouteHandler): Server;
  post(path: string, handler: RouteHandler): Server;
  put(path: string, handler: RouteHandler): Server;
  del(path: string, handler: RouteHandler): Server;
  /**
   * 挂载静态文件目录（插件数据目录 `plugins/<插件名>/` 下）：
   * `mount('web/')` → `/<file>` 从 `web/<file>` 读取（base64 二进制 + Content-Type 推断）；
   * `mount('web/', '/static')` → `/static/<file>`。文件不存在时继续后续层（最终 404）。
   */
  mount(dir: string, prefix?: string): Server;
  /**
   * 挂载**打包资源**（assets，.zip 内）为静态文件服务，无需提取到磁盘：
   * `mountAssets(getAssetsPath('web'))` → `/<file>` 从打包资源直接读取。
   * 性能略差（每次请求从 .zip 读取单个文件），适合小文件/低频访问；大文件或高频请用
   * `mount`（先提取到数据目录）。
   */
  mountAssets(dir: string, prefix?: string): Server;
  close(): void;
}

interface CompiledRoute {
  regex: RegExp;
  paramNames: string[];
  handler: RouteHandler;
  method: string;
}

/** 常见静态文件 Content-Type（按扩展名）。 */
const MIME: Record<string, string> = {
  '.html': 'text/html; charset=utf-8',
  '.htm': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.mjs': 'application/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.txt': 'text/plain; charset=utf-8',
  '.xml': 'application/xml',
  '.yaml': 'text/yaml; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.webp': 'image/webp',
  '.ico': 'image/x-icon',
  '.bmp': 'image/bmp',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.ttf': 'font/ttf',
  '.otf': 'font/otf',
  '.eot': 'application/vnd.ms-fontobject',
  '.zip': 'application/zip',
  '.gz': 'application/gzip',
  '.pdf': 'application/pdf',
  '.wasm': 'application/wasm',
  '.mp3': 'audio/mpeg',
  '.mp4': 'video/mp4',
};

function compile(path: string): { regex: RegExp; paramNames: string[] } {
  const paramNames: string[] = [];
  const regexStr =
    '^' +
    path
      .replace(/\/$/, '')
      .split('/')
      .map((seg) => {
        if (seg.startsWith(':')) {
          paramNames.push(seg.slice(1));
          return '([^/]+)';
        }
        return seg.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
      })
      .join('/') +
    '/?$';
  return { regex: new RegExp(regexStr, 'i'), paramNames };
}

export function createServer(port?: number): Server {
  /** 中间件链：use / 路由 / mount 按注册顺序压入。 */
  const middleware: Middleware[] = [];

  const srv = listen(async (raw: any) => {
    const req = raw as RouteRequest;
    let idx = 0;
    const next: NextFn = async () => {
      const mw = middleware[idx++];
      return mw ? mw(req, next) : undefined;
    };
    try {
      const result = await next();
      if (result !== undefined) {
        const opts = typeof result === 'string' ? { body: result } : result;
        respond(req.serverId, req.connId, opts);
        return;
      }
    } catch (e) {
      respond(req.serverId, req.connId, { status: 500, body: 'Internal Server Error' });
      return;
    }
    // 全部层未产生响应
    respond(req.serverId, req.connId, { status: 404, body: 'Not Found' });
  }, port);

  const api: Server = {
    port: srv.port,

    use(mw) {
      middleware.push(mw);
      return api;
    },

    get(path, handler) { return route('GET', path, handler); },
    post(path, handler) { return route('POST', path, handler); },
    put(path, handler) { return route('PUT', path, handler); },
    del(path, handler) { return route('DELETE', path, handler); },

    mount(dir, prefix = '/') {
      return mountStatic(fs.readFileBase64, dir, prefix);
    },

    mountAssets(dir, prefix = '/') {
      return mountStatic(assetsReadBase64, dir, prefix);
    },

    close() { if (srv.serverId) close(srv.serverId); },
  };

  /** 静态文件中间件（共享 mount / mountAssets）：路径解析（防穿越）+ Content-Type 推断 + base64 响应。 */
  function mountStatic(read: (filePath: string) => Promise<string>, dir: string, prefix: string): Server {
    // 规范化：'web/' → 'web'；URL 前缀 '/' → ''，'/static/' → '/static'
    const baseDir = String(dir || '').replace(/\/+$/, '');
    const base = String(prefix || '/').replace(/\/+$/, '');
    middleware.push(async (req, next) => {
      const p = req.path || '/';
      // 解析相对文件路径（防穿越：拒绝含 .. 段的路径）
      let rel: string | null = null;
      if (base === '' || base === '/') {
        rel = p.startsWith('/') ? p.slice(1) : p;
      } else if (p.startsWith(base + '/')) {
        rel = p.slice(base.length + 1);
      } else if (p === base) {
        rel = '';
      }
      if (rel === null) return next();
      if (rel.split('/').includes('..')) return next();
      if (rel === '') return next();
      const filePath = baseDir + '/' + rel;
      let data: string;
      try {
        data = await read(filePath);
      } catch {
        return next(); // 文件不存在 → 继续后续层（最终 404）
      }
      const dot = rel.lastIndexOf('.');
      const ext = dot >= 0 ? rel.slice(dot).toLowerCase() : '';
      return {
        status: 200,
        bodyBase64: data,
        headers: { 'content-type': MIME[ext] || 'application/octet-stream' },
      };
    });
    return api;
  }

  function route(method: string, path: string, handler: RouteHandler): Server {
    const cr = compile(path);
    middleware.push(async (req, next) => {
      if (req.method.toUpperCase() !== method) return next();
      const m = req.path.match(cr.regex);
      if (!m) return next();
      req.params = {};
      for (let i = 0; i < cr.paramNames.length; i++) {
        req.params[cr.paramNames[i]] = decodeURIComponent(m[i + 1]);
      }
      const result = await handler(req);
      return result !== undefined ? result : next(); // handler 未返回 → 继续后续层
    });
    return api;
  }

  return api;
}
