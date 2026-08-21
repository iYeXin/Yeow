# Worker API (virtual plugins)

A Worker is a **virtual plugin** — an independent QuickJS context + independent thread that shares the data directory and permissions with its main plugin, used for CPU-intensive computation, parallel batch tasks, and long-running tasks isolated from the main plugin's thread.

```js
import { createWorker, onMessage, postMessage } from 'yeow-api';
```

## Main plugin side: createWorker

```js
const worker = createWorker({
    name: 'web-worker',                        // required; 'main' not allowed; unique within the same main plugin (repeatable globally)
    entry: getAssetsPath('worker/web-worker.js'),  // asset path (via getAssetsPath); mutually exclusive with code
    // code: '...',                             // code string; mutually exclusive with entry
});
// createWorker only registers it in the registry and returns a handle — worker.load() actually starts it

await worker.load();            // boot: init.js → worker-inject.js → Worker code → INIT → LOAD (no-op if already loaded)
worker.onMessage((msg) => {     // receive messages sent from the Worker
    console.log('from worker:', msg);
});
await worker.postMessage({ task: 'compute', data: [...] });   // send to the Worker
await worker.reload();          // reload code (requires it to be loaded; destroys the old context, reloads)
await worker.unload();          // unload (physically destroy the JS context and clean up its events/commands/services/tasks)
await worker.load();            // after unload the handle is kept — it can be reloaded
```

Validation: `entry` and `code` cannot both be passed (throws); `name` is required, cannot be `'main'`, and creating a duplicate within the same main plugin throws.

## Worker side

```js
import { onMessage, postMessage, onLoad } from 'yeow-api';

onLoad(() => {                  // lifecycle (triggered on LOAD, same as plugins)
    console.log('worker ready');
});

onMessage((msg) => {            // receive messages from the main plugin (triggered by worker.postMessage)
    // process the task…
    postMessage({ result: 42 });   // send back to the main plugin (received by worker.onMessage on the main plugin side)
});
```

Developing a Worker is similar to developing a normal plugin: the full yeow-api is available (task/fs/http/assets/service/timer, etc.).

## Semantics and limitations

| Item       | Behavior                                                                                                                                    |
| -------- | --------------------------------------------------------------------------------------------------------------------------------------- |
| Independent entity | Events/commands/services are registered as an independent entity (`<mainPlugin>.<worker>`); scheduler tasks are counted/cleaned up independently                                                            |
| Data directory | **Shares the main plugin's data directory** (fs plugin level = `plugins/<mainPlugin>/`)                                                                         |
| Assets | **Shares the main plugin's assets** (same namespace on the assets channel)                                                                                           |
| Permissions | **Shares the main plugin's permissions** (no independent declarations)                                                                                                        |
| Nesting | **Cannot create new Workers** (the worker channel is rejected)                                                                                            |
| Lifecycle | When the main plugin is unloaded or hot-reloaded, **all its Workers are unloaded along with it**; **once created, a Worker cannot be destroyed, only unloaded** (`unload` physically destroys the JS context, the handle is kept, and it can be `load`ed again) |
| Admin command | `/yeow` management commands do **not cover** Workers                                                                                                       |
| Performance monitoring | The profiler counts Workers (marked `(worker of <mainPlugin>)`), and alerts detect them as well                                                                       |
| Error reporting | Worker JS errors are reported back just like the main plugin's (dev-mode sourcemap localization, showing `JS Error in Worker <name>`)                                         |

> **Registration name**: a Worker is registered as `<mainPlugin>.<workerName>` (guaranteed globally unique); `__plugin.name` is this registration name.

## Development approach (build chain)

`dev.worker` in `yeow.config.json` configures worker bundling:

```json
{
    "dev": {
        "worker": [
            { "name": "web-worker", "entry": "worker/web-worker/index.ts", "dist": "assets/worker/web-worker.js" }
        ]
    }
}
```

- At build time, **the workers are bundled first** (esbuild, output to the asset directory `assets/<id>/worker/<name>.js`), then the main plugin — the main plugin reads them via `getAssetsPath(dist)`
- Hot reload automatically detects changes in the `entry` directory (rebuild + main plugin hot-reload, and the Worker is rebuilt accordingly)
- In dev mode, worker bundles include sourcemaps — Worker errors are traced back to source (`JS Error in Worker <name>` + code context)
- Workers share the `yeow-api` dependency with the main plugin

**For dependency package authors**: debug in a real project, and after testing succeeds place the bundled worker file into the asset directory yourself.
