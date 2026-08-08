/** env 通道返回的运行时环境信息（同步）。 */
export interface EnvInfo {
  /** CPU 逻辑核心数。 */
  cpus: number;
  /** JVM 总内存（字节）。 */
  memory: number;
  /** 系统架构（如 `windows-x64` / `linux-x64` / `linux-arm64`）。 */
  arch: string;
  /** Minecraft 版本（如 `1.21.4`）。 */
  minecraftVersion: string;
  /** 运行时信息。 */
  yeow: { platform: string; version: string };
  /** epoch 微秒时间戳。 */
  now: number;
}

/** 获取运行时环境信息（同步；含微秒时间戳）。 */
export function getEnv(): EnvInfo {
  return $send('env', {}) as EnvInfo;
}
