export interface TaskOptions {
  /** 任务优先级：high / normal（默认）/ low */
  priority?: 'high' | 'normal' | 'low';
}

/** 透传参数：TaskOptions 对象，或旧式字符串优先级。 */
type TaskOptionsArg = TaskOptions | string;

function applyOptions(pld: Record<string, unknown>, options?: TaskOptionsArg) {
  if (!options) return;
  pld.priority = typeof options === 'string' ? options : options.priority;
}

export function post<T = unknown>(
  type: string,
  params: Record<string, unknown> = {},
  options?: TaskOptionsArg
): Promise<T> {
  return new Promise((resolve, reject) => {
    const cbId = _registerCallback((result: any) => {
      if (result?.err) {
        const msg = result.type ? `[${result.type}] ${result.err}` : result.err;
        const e = new Error(msg);
        if ($dev && typeof _attachCbStack === 'function') {
          _attachCbStack(e);
        }
        if (result.stack) {
          e.stack += '\n    --- runtime executer error(for reference) ---\n' + result.stack;
        }
        (e as any).javaType = result.type || null;
        (e as any).taskType = result.task || null;
        reject(e);
      } else resolve(result as T);
    });
    const pld: Record<string, unknown> = { type, params, cb: cbId };
    applyOptions(pld, options);
    $send('task', pld);
  });
}

export function call<T = unknown>(
  type: string,
  params: Record<string, unknown> = {},
  options?: TaskOptionsArg
): T {
  const pld: Record<string, unknown> = { type, params };
  applyOptions(pld, options);
  const r = $send('task', pld);
  if (r == null) return undefined as T;
  if ((r as any)?.err) throw new Error((r as any).err);
  return r as T;
}
