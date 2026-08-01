// yeow-dev 是构建期虚拟模块：import 在构建时被 Yeow 构建器（yeow-assets.mjs）
// 拦截，按 importer 所属依赖项注入对应资产的命名空间 id。
// 此文件不会被实际加载——仅保证包在 npm 上的可解析性。
export {};
