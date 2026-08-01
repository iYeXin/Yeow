/// <reference path="assets-path.d.ts" />

import { getPath as _getPath } from '__yeow-assets';

export function getAssetsPath(path: string): string {
    return _getPath(path);
}
