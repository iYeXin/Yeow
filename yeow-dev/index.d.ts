/**
 * 获取插件包内 assets/ 中资源的运行时路径（构建期替换）。
 *
 * 由 Yeow 构建器按调用方所属依赖项注入命名空间，返回形如
 * `assets/<id>/<path>` 的路径；传给所有 assets API 与 Native Service
 * 的 `platforms` 配置。未构建（直接运行 TS）时此文件同样不会被加载。
 */
export declare function getAssetsPath(path: string): string;
