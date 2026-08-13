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
  if ((r as any)?.err) {
    // 与 post() 对齐的错误上下文（type/task/Java 堆栈），2026-08-13 审计修复
    const errObj = r as any;
    const msg = errObj.type ? `[${errObj.type}] ${errObj.err}` : errObj.err;
    const e = new Error(msg);
    if (errObj.stack) {
      e.stack += '\n    --- runtime executer error(for reference) ---\n' + errObj.stack;
    }
    (e as any).javaType = errObj.type || null;
    (e as any).taskType = errObj.task || null;
    throw e;
  }
  return r as T;
}

// ══════════════════════════════════════════════════════════════════
// 批量任务：一次提交任务数组，结果按原顺序返回（逐个独立执行，无原子性）。
// 依赖包可基于此构造自己的批量优化（如批量发物品、批量写方块）。
// ══════════════════════════════════════════════════════════════════

export interface BatchTask {
  type: string;
  params?: Record<string, unknown>;
  priority?: 'high' | 'normal' | 'low';
}

/** 同步批量：阻塞直到全部任务完成，返回结果数组（顺序对应 tasks；单个任务失败时对应项为 `{err}` 对象）。 */
export function callBatch(tasks: BatchTask[]): unknown[] {
  if (tasks.length === 0) return [];
  const r = $send('task', { tasks });
  if (r == null) return [];
  if ((r as any)?.err) throw new Error((r as any).err);
  return r as unknown[];
}

/** 异步批量：全部任务完成后 Promise resolve 结果数组（顺序对应 tasks；单个任务失败时对应项为 `{err}` 对象）。 */
export function postBatch<T = unknown>(tasks: BatchTask[]): Promise<T[]> {
  return new Promise((resolve, reject) => {
    if (tasks.length === 0) { resolve([] as T[]); return; }
    const cbId = _registerCallback((result: any) => {
      if (result?.err) {
        const msg = result.type ? `[${result.type}] ${result.err}` : result.err;
        reject(new Error(msg));
      } else {
        resolve(result as T[]);
      }
    });
    $send('task', { tasks, cb: cbId });
  });
}
