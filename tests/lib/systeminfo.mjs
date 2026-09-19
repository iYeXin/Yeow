// 测试平台信息（CPU 型号/物理核、内存总量与规格、OS、Node）。
// 优先使用 systeminformation（含内存类型/频率），不可用时回退 node:os。
import os from 'node:os';

async function loadSi() {
  try {
    const m = await import('systeminformation');
    return m.default ?? m;
  } catch {
    return null;
  }
}

const gb = (bytes) => (bytes / 1024 ** 3).toFixed(1);

export async function collectSystemInfo() {
  const info = {
    os: `${os.type()} ${os.release()} (${os.arch()})`,
    node: process.version,
  };
  const si = await loadSi();
  if (si) {
    try {
      const [cpu, mem, layout] = await Promise.all([si.cpu(), si.mem(), si.memLayout()]);
      info.cpu = `${cpu.manufacturer} ${cpu.brand} (${cpu.physicalCores}c/${cpu.cores}t)`.replace(/\s+/g, ' ').trim();
      const mods = (layout || [])
        .filter((m) => m.size)
        .map((m) => `${Math.round(m.size / 1024 ** 3)}GB${m.type ? ' ' + m.type : ''}${m.clockSpeed ? '@' + m.clockSpeed + 'MHz' : ''}`)
        .join(' + ');
      info.memory = `${gb(mem.total)} GB` + (mods ? ` [${mods}]` : '');
    } catch {
      /* fall back below */
    }
  }
  if (!info.cpu) info.cpu = `${os.cpus()[0]?.model ?? 'unknown'} (${os.cpus().length}t)`;
  if (!info.memory) info.memory = `${gb(os.totalmem())} GB`;
  return info;
}

export function formatSystemInfo(info) {
  return `cpu:  ${info.cpu}\nram:  ${info.memory}\nos:   ${info.os}  node: ${info.node}`;
}
