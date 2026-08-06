/**
 * Yeow API 入口。
 *
 * 全部具名导出来自 `core.ts`（与历史导出面完全一致）；默认导出 `Yeow` 为
 * 聚合全部具名导出的**大对象**——不推荐使用：默认导入会破坏 tree-shaking，
 * 显著增大插件体积，仅适用于简化动态执行含任意逻辑的代码。请按需命名导入。
 */
export * from './core.js';

import * as api from './core.js';

const Yeow = { ...api };

export default Yeow;
export { Yeow };
