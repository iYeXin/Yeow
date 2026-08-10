// 备选声明：插件**未安装** yeow-dev（构建期虚拟模块）时提供 getAssetsPath 类型。
// 注意：本文件必须为**非模块**（无 import/export）——ambient `declare module` 在
// 模块文件中会被视为模块增强（需要先 import 才生效），独立使用报 Cannot find module。
// 已安装 yeow-dev 时以 node_modules/yeow-dev/index.d.ts 为准（内容一致）。
declare module 'yeow-dev' {
  export function getAssetsPath(path: string): string;
}
