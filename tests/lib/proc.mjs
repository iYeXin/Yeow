// 共享进程执行工具：统一 spawnSync、退出码、输出转发。
import { spawnSync } from 'node:child_process';

/**
 * 同步执行命令。
 * @param {string} cmd
 * @param {string[]} args
 * @param {{cwd?: string, inherit?: boolean, env?: Record<string,string>, label?: string}} [opts]
 * @returns {{ok: boolean, code: number, stdout: string, stderr: string}}
 */
export function run(cmd, args, opts = {}) {
  const res = spawnSync(cmd, args, {
    cwd: opts.cwd,
    env: { ...process.env, ...(opts.env || {}) },
    stdio: opts.inherit ? 'inherit' : ['ignore', 'pipe', 'pipe'],
    encoding: 'utf8',
    shell: false,
  });
  const ok = res.status === 0;
  if (!opts.inherit && !ok) {
    const label = opts.label || `${cmd} ${args.join(' ')}`;
    process.stderr.write(`\n[proc] ${label} exited ${res.status}\n`);
    if (res.stdout) process.stderr.write(res.stdout + '\n');
    if (res.stderr) process.stderr.write(res.stderr + '\n');
  }
  return { ok, code: res.status ?? -1, stdout: res.stdout || '', stderr: res.stderr || '' };
}

/** 定位可执行文件：优先 $JAVA_HOME/bin，其次 PATH。 */
export function exe(name) {
  const home = process.env.JAVA_HOME;
  if (home) return `${home}/bin/${name}`;
  return name;
}
