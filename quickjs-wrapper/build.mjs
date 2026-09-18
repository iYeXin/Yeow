#!/usr/bin/env node
// quickjs-wrapper 构建入口：先生成 polyfill JS 的 C 头，再调用 zig 构建。
//
//   node build.mjs                          # 本机平台动态库
//   node build.mjs all                      # 全部平台 -> zig-out/native/<platform>/
//   node build.mjs jar                      # Java 类 + 全部平台原生库 -> zig-out/yeow-quickjs.jar
//   node build.mjs -Dtarget=aarch64-linux-gnu
//
// 参数原样透传给 `zig build`。代码生成（native/polyfill/js/*.js -> js.generated.h）
// 与构建编排属于 build.mjs；build.zig 只负责编译与打包。
import { spawnSync } from 'node:child_process';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)));

function run(cmd, args) {
  const r = spawnSync(cmd, args, { cwd: ROOT, stdio: 'inherit', shell: false });
  if ((r.status ?? 1) !== 0) process.exit(r.status ?? 1);
}

run(process.execPath, [resolve(ROOT, 'scripts', 'gen-polyfill.mjs')]);
run('zig', ['build', ...process.argv.slice(2)]);
