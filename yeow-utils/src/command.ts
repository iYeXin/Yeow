import type { CommandSender, Permission, PermissionOptions } from 'yeow-api';
import { Player, registerCommand } from 'yeow-api';

type NodeType = 'enum' | 'number' | 'player' | 'world' | 'string' | 'pos' | 'angel' | 'bool';

interface SchemaNode {
  type: NodeType;
  name: string;
  values?: string[];
  count: number;
  required: boolean;
}

export class CommandSchema<T extends SchemaNode[] = SchemaNode[]> {
  private nodes: SchemaNode[] = [];

  enum<N extends string, R extends boolean = true>(name: N, values: string[], required?: R): CommandSchema<[...T, { type: 'enum'; name: N; values: string[]; count: 1; required: R }]> {
    this.nodes.push({ type: 'enum', name, values, count: 1, required: required ?? true as boolean }); return this as any;
  }
  number<N extends string, R extends boolean = true>(name: N, required?: R): CommandSchema<[...T, { type: 'number'; name: N; count: 1; required: R }]> {
    this.nodes.push({ type: 'number', name, count: 1, required: required ?? true as boolean }); return this as any;
  }
  string<N extends string, R extends boolean = true>(name: N, required?: R): CommandSchema<[...T, { type: 'string'; name: N; count: 1; required: R }]> {
    this.nodes.push({ type: 'string', name, count: 1, required: required ?? true as boolean }); return this as any;
  }
  player<N extends string, R extends boolean = true>(name: N, required?: R): CommandSchema<[...T, { type: 'player'; name: N; count: 1; required: R }]> {
    this.nodes.push({ type: 'player', name, count: 1, required: required ?? true as boolean }); return this as any;
  }
  world<N extends string, R extends boolean = true>(name: N, required?: R): CommandSchema<[...T, { type: 'world'; name: N; count: 1; required: R }]> {
    this.nodes.push({ type: 'world', name, count: 1, required: required ?? true as boolean }); return this as any;
  }
  bool<N extends string, R extends boolean = true>(name: N, required?: R): CommandSchema<[...T, { type: 'bool'; name: N; count: 1; required: R }]> {
    this.nodes.push({ type: 'bool', name, count: 1, required: required ?? true as boolean }); return this as any;
  }
  pos<N extends string, R extends boolean = true>(name: N, required?: R): CommandSchema<[...T, { type: 'pos'; name: N; count: 3; required: R }]> {
    this.nodes.push({ type: 'pos', name, count: 3, required: required ?? true as boolean }); return this as any;
  }
  angel<N extends string, R extends boolean = true>(name: N, required?: R): CommandSchema<[...T, { type: 'angel'; name: N; count: 2; required: R }]> {
    this.nodes.push({ type: 'angel', name, count: 2, required: required ?? true as boolean }); return this as any;
  }
  _nodes(): T { return [...this.nodes] as T; }
}

type NodeTypeMap = { enum: string; number: number; player: string; world: string; string: string; bool: boolean; pos: number[]; angel: number[] };
type InferParsed<N extends SchemaNode> = N['type'] extends keyof NodeTypeMap ? NodeTypeMap[N['type']] : string;
type SchemaToParsed<T extends SchemaNode[]> = {
  [K in T[number] as K['name']]: K['required'] extends false ? InferParsed<K> | undefined : InferParsed<K>;
};
type ExtractNodes<S> = S extends CommandSchema<infer N> ? N : never;

// Type-level: reject schemas with required params after optional
type ReqAfterOpt = { __yeow_error: 'Required parameter cannot follow optional parameter'; };

type ValidNodes<T extends SchemaNode[], Seen extends boolean = false> =
  T extends [infer H extends SchemaNode, ...infer R extends SchemaNode[]]
    ? H['required'] extends true
      ? Seen extends true ? ReqAfterOpt : ValidNodes<R, false>
      : ValidNodes<R, true>
    : T;

type CheckedSchema<S extends CommandSchema> = S extends CommandSchema<infer N> ? (ValidNodes<N> extends ReqAfterOpt ? ReqAfterOpt : S) : S;

// ── Completer ───────────────────────────────────────────────────────

type SuggestResult = string[] | Promise<string[]>;

export type CompleterOptions = Record<string, {
  enum?: string[] | string[][];
  completer?: (parsed: Record<string, any>, localIndex: number) => SuggestResult;
  default?: string | string[];
  maxSuggestions?: number;
}>;

export class Completer {
  private _build: (schema?: CommandSchema) => {
    suggest: (sender: CommandSender, args: string[]) => SuggestResult;
    parse: (args: string[], sender?: CommandSender) => Record<string, any>;
    match: (args: string[]) => number;
  };

  constructor(schemaOrFn: CommandSchema | ((sender: CommandSender, args: string[]) => string[]), options?: CompleterOptions) {
    if (typeof schemaOrFn === 'function') {
      const fn = schemaOrFn;
      this._build = (_schema) => {
        if (_schema) {
          const h = buildHandlers(_schema._nodes(), options);
          return { suggest: fn as any, parse: h.parse, match: h.match };
        }
        return { suggest: fn as any, parse: () => ({}), match: () => 0 };
      };
    } else {
      this._build = () => buildHandlers(schemaOrFn._nodes(), options);
    }
  }

  _getBuild(schema?: CommandSchema) { return this._build(schema); }
}

function buildHandlers(nodes: SchemaNode[], options?: CompleterOptions) {
  return {
    suggest: (sender: CommandSender, cmdArgs: string[]): SuggestResult => {
      const idx = cmdArgs.length - 1;
      if (idx < 0) return [];
      const typed = (cmdArgs[idx] || '').toLowerCase();
      let argPos = 0;
      for (const node of nodes) {
        const end = argPos + node.count;
        if (idx < end) {
          const localIdx = idx - argPos;
          const ov = options?.[node.name];

          if (ov?.completer) {
            const partialParsed: Record<string, any> = {};
            let p = 0;
            for (const n of nodes) {
              const vals: string[] = [];
              for (let i = 0; i < n.count && p < cmdArgs.length; i++, p++) vals.push(cmdArgs[p]);
              if (n === node) {
                try {
                  if (n.count === 1) {
                    partialParsed[n.name] = n.type === 'number' ? parseFloat(vals[0] ?? '') || 0 : vals[0] ?? '';
                  } else {
                    const nums = n.type === 'pos' || n.type === 'angel'
                      ? vals.map((v) => parseFloat(v) || 0)
                      : vals;
                    if (vals.length === n.count) partialParsed[n.name] = nums;
                  }
                } catch { /* ignore */ }
              }
              if (p > idx) break;
            }
            const r = ov.completer(partialParsed, localIdx);
            if (r instanceof Promise) return r;
            if (r && r.length > 0) return r;
          }

          if (ov?.enum) {
            const e = ov.enum;
            if (Array.isArray(e[0])) {
              const arr = e as string[][];
              if (arr[localIdx]) return filterEnum(arr[localIdx], typed, ov.maxSuggestions);
            }
            return filterEnum(e as string[], typed, ov.maxSuggestions);
          }

          if (ov?.default) {
            const ph = ov.default;
            if (typeof ph === 'string') return [ph];
            if (ph[localIdx]) return [ph[localIdx]];
            return [ph[0] || '<value>'];
          }

          return suggestForNode(node, localIdx, typed, ov?.maxSuggestions, sender);
        }
        argPos = end;
        if (cmdArgs.length <= argPos && !node.required) return [];
      }
      return [];
    },
    parse: (args: string[], sender?: CommandSender): Record<string, any> => {
      const result: Record<string, any> = {};
      let argPos = 0;
      for (const node of nodes) {
        const values: string[] = [];
        for (let i = 0; i < node.count && argPos < args.length; i++, argPos++) values.push(args[argPos]);
        if (node.count === 1) {
          const raw = values[0] ?? '';
          switch (node.type) {
            case 'number': result[node.name] = parseFloat(raw) || 0; break;
            case 'bool': result[node.name] = raw === 'true'; break;
            default: result[node.name] = raw;
          }
        } else {
          switch (node.type) {
            case 'pos': result[node.name] = resolvePos(values, sender); break;
            case 'angel': result[node.name] = values.map((v) => parseFloat(v) || 0); break;
            default: result[node.name] = values;
          }
        }
      }
      return result;
    },
    match: (args: string[]): number => {
      let argPos = 0;
      for (const node of nodes) {
        const available = args.length - argPos;
        if (node.required && available <= 0) return -1;
        if (available < node.count) break;
        // enum 值校验：token 与声明值不匹配 → 本重载不匹配。
        // 否则并列重载只看 token 数、先注册者胜——`paste <name>` 会吃掉 `info <name>` 等
        // （action 为 enum 的 2-token 重载）。
        if (node.type === 'enum' && node.values && !node.values.includes(args[argPos])) return -1;
        argPos += node.count;
      }
      return argPos;
    },
  };
}

// ── Command creation ──────────────────────────────────

export interface CommandOptions {
  description?: string;
  /** 权限节点：字符串（兼容）或权限节点对象 `{ node, default? }` / `registerPermission` 返回值——与 yeow-api `registerCommand` 语义一致 */
  permission?: string | Permission | PermissionOptions;
  aliases?: string[];
  usage?: string;
  default?: (p: { sender: CommandSender; args: string[]; label: string }) => void;
}

function resolveCompleter(schema: CommandSchema, c: Completer | CompleterOptions | undefined): Completer {
  if (c instanceof Completer) return c;
  return new Completer(schema, c ?? {});
}

export class CommandBuilder {
  private overloads: { schema: CommandSchema; executor: (p: any) => void; completer?: Completer | CompleterOptions }[] = [];
  private _sealed = false;

  add<S extends CommandSchema>(
    schema: CheckedSchema<S>,
    executor: (p: { sender: CommandSender; args: string[]; label: string; parsed: SchemaToParsed<ExtractNodes<S>> }) => void,
    completer?: Completer | CompleterOptions,
  ): this {
    if (this._sealed) throw new Error('Cannot add to a sealed command');
    const nodes = schema._nodes();
    let seenOptional = false;
    for (const n of nodes) {
      if (n.required && seenOptional) throw new Error(`Required param '${n.name}' follows optional`);
      if (!n.required) seenOptional = true;
    }
    this.overloads.push({ schema, executor, completer });
    return this;
  }

  build(): void {
    if (this._sealed) return;
    this._sealed = true;

    const built = this.overloads.map((o) => ({
      handlers: resolveCompleter(o.schema, o.completer)._getBuild(o.schema),
      executor: o.executor,
    }));

    const options = (this as any)._options as CommandOptions;
    const usageMsg = options.usage;

    const spec = {
      description: options.description,
      permission: options.permission,
      aliases: options.aliases,
      executor: (p: { sender: CommandSender; args: string[]; label: string }) => {
        let bestScore = -1;
        let bestHandler: typeof built[0] | null = null;
        for (const h of built) {
          const score = h.handlers.match(p.args);
          if (score > bestScore) { bestScore = score; bestHandler = h; }
        }
        if (bestHandler) {
          const parsed = bestHandler.handlers.parse(p.args, p.sender);
          bestHandler.executor({ ...p, parsed });
          return;
        }
        if (options.default) { options.default(p); }
        else if (usageMsg) {
          if (p.sender === 'CONSOLE') console.log(usageMsg);
          else p.sender.sendMessage(usageMsg);
        }
      },
      completer: (sender: CommandSender, args: string[]): string[] | Promise<string[]> => {
        const idx = args.length - 1;
        if (idx < 0) return [];
        const suggestions: Set<string> = new Set();
        let hasAsync = false;
        let asyncResult: Promise<string[]> | null = null;
        for (const h of built) {
          const score = h.handlers.match(args);
          if (score < 0) continue;
          const result = h.handlers.suggest(sender, args);
          if (result instanceof Promise) { hasAsync = true; asyncResult = result; continue; }
          for (const s of result) suggestions.add(s.toLowerCase());
          if (suggestions.size >= 200) break;
        }
        if (hasAsync && suggestions.size === 0 && asyncResult) return asyncResult;
        return [...suggestions];
      },
    };

    (this as any)._spec = spec;
  }

  _spec: import('yeow-api').CommandOptions | null = null;
}

export const Command = {
  create(name: string, options?: CommandOptions): CommandBuilder {
    const b = new CommandBuilder();
    (b as any)._name = name;
    (b as any)._options = options ?? {};
    return b;
  },

  register(cmd: CommandBuilder): void {
    cmd.build();
    const name = (cmd as any)._name as string | undefined;
    if (!name) throw new Error('Command name not set');
    registerCommand(name, cmd._spec!);
  },
};

function resolvePos(values: string[], sender?: CommandSender): number[] {
  const nums = values.map((v): { relative: boolean; offset: number; value: number } => {
    if (v.startsWith('~')) {
      const offset = v.length > 1 ? parseFloat(v.substring(1)) : 0;
      return { relative: true, offset: isNaN(offset) ? 0 : offset, value: 0 };
    }
    return { relative: false, offset: 0, value: parseFloat(v) || 0 };
  });
  if (nums.some((n) => n.relative)) {
    if (sender !== 'CONSOLE') {
      try {
        const player = sender as import('yeow-api').Player;
        const loc = player.location;
        if (loc) {
          const base: number[] = [loc.x, loc.y, loc.z];
          return nums.map((n, i) => (n.relative ? base[i] + n.offset : n.value));
        }
      } catch { /* ignore */ }
    }
    return nums.map((n) => (n.relative ? n.offset : n.value));
  }
  return nums.map((n) => n.value);
}

function suggestForNode(node: SchemaNode, localIdx: number, typed: string, maxSuggestions: number | undefined, sender: CommandSender): string[] {
  switch (node.type) {
    case 'enum': return filterEnum(node.values || [], typed, maxSuggestions);
    case 'player':
      try { return filterEnum(Player.getAllSync().map((p) => p.name).filter(Boolean), typed, maxSuggestions); }
      catch { return ['<player>']; }
    case 'world': return ['<world>'];
    case 'number': return ['<num>'];
    case 'string': return ['<value>'];
    case 'bool': return filterEnum(['true', 'false'], typed, maxSuggestions);
    case 'pos': return [['<x>'], ['<y>'], ['<z>']][localIdx] || [];
    case 'angel': return [['<yaw>'], ['<pitch>']][localIdx] || [];
    default: return [];
  }
}

const SUGGEST_LIMIT = 100;

function filterEnum(values: string[], typed: string, max?: number): string[] {
  const limit = max ?? SUGGEST_LIMIT;
  if (!typed) return values.length <= limit ? values : values.slice(0, limit);
  const lower = typed.toLowerCase();
  const result: string[] = [];
  for (const v of values) {
    if (v.toLowerCase().includes(lower)) { result.push(v); }
    else {
      const colonIdx = v.indexOf(':');
      if (colonIdx !== -1) {
        const afterColon = v.substring(colonIdx + 1).toLowerCase();
        if (afterColon.includes(lower)) result.push(v);
      }
    }
    if (result.length >= limit) break;
  }
  return result;
}
