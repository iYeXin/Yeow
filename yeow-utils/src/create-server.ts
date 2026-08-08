import { listen, respond, close } from 'yeow-api';
import type { RespondOptions } from 'yeow-api';

/** 响应体：字符串（文本）或完整响应选项（含 bodyBase64 二进制）。 */
export type ResponseBody = RespondOptions;

/** 路由 handler：返回字符串（文本响应）、响应选项对象（如 { bodyBase64, headers }），或不返回（自行 respond）。 */
export type RouteHandler = (
  req: RouteRequest,
) => string | ResponseBody | undefined | Promise<string | ResponseBody | undefined>;

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
  get(path: string, handler: RouteHandler): Server;
  post(path: string, handler: RouteHandler): Server;
  put(path: string, handler: RouteHandler): Server;
  del(path: string, handler: RouteHandler): Server;
  close(): void;
}

interface CompiledRoute {
  regex: RegExp;
  paramNames: string[];
  handler: RouteHandler;
}

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
  const routes: Record<string, CompiledRoute[]> = {};

  const srv = listen(async (raw: any) => {
    const req = raw as RouteRequest;
    const method = (req.method || 'GET').toUpperCase();
    const compiled = routes[method] || [];
    for (const cr of compiled) {
      const m = req.path.match(cr.regex);
      if (m) {
        req.params = {};
        for (let i = 0; i < cr.paramNames.length; i++) {
          req.params[cr.paramNames[i]] = decodeURIComponent(m[i + 1]);
        }
        try {
          // 支持异步 handler：await 结果后再 respond（Promise 不会被当作 body 回传）
          const result = await cr.handler(req);
          if (result !== undefined) {
            const opts = typeof result === 'string' ? { body: result } : result;
            respond(req.serverId, req.connId, opts);
            return;
          }
        } catch (e) {
          respond(req.serverId, req.connId, { status: 500, body: 'Internal Server Error' });
          return;
        }
      }
    }
    // No match
    respond(req.serverId, req.connId, { status: 404, body: 'Not Found' });
  }, port);

  const addRoute = (method: string, path: string, handler: (req: RouteRequest) => any) => {
    const cr = compile(path);
    (routes[method] ??= []).push({ ...cr, handler });
  };

  const api: Server = {
    port: srv.port,
    get(path, handler) { addRoute('GET', path, handler); return api; },
    post(path, handler) { addRoute('POST', path, handler); return api; },
    put(path, handler) { addRoute('PUT', path, handler); return api; },
    del(path, handler) { addRoute('DELETE', path, handler); return api; },
    close() { if (srv.serverId) close(srv.serverId); },
  };
  return api;
}
