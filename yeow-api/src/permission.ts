import { call } from './task.js';
import type { TaskOptions } from './task.js';

/** 权限节点默认值：'all' = 所有人默认拥有；'op' = 仅 op（默认）；'none' = 需授权。 */
export type PermissionDefault = 'all' | 'op' | 'none';

export interface PermissionOptions {
  /** 权限节点（如 `myplugin.home`）。 */
  node: string;
  /** 默认值（默认 'op'）。 */
  default?: PermissionDefault;
}

/** 权限节点对象（注册后返回）。 */
export interface Permission {
  node: string;
  default: PermissionDefault;
}

let _registered: Record<string, Permission> = {};

/**
 * 注册权限节点（幂等）：节点注册进 Bukkit 权限系统——Paper 平台可通过
 * `permissions.yml` 静态声明或 LuckPerms 等权限管理插件管理。
 *
 * 粒度较粗（只声明默认值）；Yeow 生态内可由权限管理插件通过 `permissionCheck`
 * 事件实现任意逻辑的权限管理。
 */
export function registerPermission(options: PermissionOptions, taskOptions?: TaskOptions): Permission {
  const node = options?.node;
  if (!node || typeof node !== 'string' || node.trim() === '') {
    throw new Error('registerPermission: node is required');
  }
  const def: PermissionDefault = options.default || 'op';
  if (!_registered[node]) {
    call('permission.register', { node, default: def }, taskOptions);
    _registered[node] = { node, default: def };
  }
  return _registered[node];
}

/** 权限节点对象 → 请求载荷（{ node, default }）；字符串包装为对象（default 默认 'op'）——Java 侧不做兼容。 */
export function permissionPayload(perm: string | Permission | PermissionOptions): { node: string; default: PermissionDefault } {
  if (typeof perm === 'string') return { node: perm, default: 'op' };
  return { node: perm.node, default: perm.default || 'op' };
}
