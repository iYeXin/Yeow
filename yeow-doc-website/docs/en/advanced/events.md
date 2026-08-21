# Events and Callbacks

> Event Bridge (EventBridge): concurrent/serial modes, event data, handler operations and mode selection; unified callback system.

## EventBridge

```
Paper-based event triggered
  → EventBridge: check JS subscriptions
  → eventData() extract fields (primitive types, no platform object references)
  → SyncCallbackHelper.register(cbId)
  → queue.sendJs({t:"cb", p:cbId, r:{event data}}) → JS thread
  → Main thread spin-waits (no budget to drain scheduler queue — event.complete is not starved by tick time-slice budget):
      while (not completed && not timed out) {
          runtime.getScheduler().drainAll();
          Thread.onSpinWait();
      }
  → JS $hm → _hm → _cbs[cbId].h(r)
  → yeow-api callback:
      Auto mode:
        Synchronous handler: executes → collects modifications (return value mods + direct assignment of event parameters) → $send('task', {type:'event.complete', params:{eventId, mods}})
        Returns Promise: → immediately $send('event.complete'), only synchronous-phase modifications take effect
      Manual mode:
        handler(e, complete) → user calls complete(mods)
  → Scheduler → Tasks.execute('event.complete')
  → SyncCallbackHelper.complete(cbId, mods)
  → applyMods(): applies write-back fields (cancelled / joinMessage / quitMessage / message / format / to / respawnLocation / deathMessage / newFoodLevel / damage / amount / target / clickedItem / cursorItem / serverPing motd, etc. — full list see api/event.md event write-back)
```

## Concurrent Event Handling

When multiple plugins subscribe to the same event, Yeow supports both serial (default) and concurrent modes:

| Mode | Behavior | runtime/config.yml Setting |
| ---- | -------- | -------------------------- |
| Serial | Sends the event to each plugin one by one, waiting for each to complete before sending to the next | `concurrent-events: false` |
| Concurrent (Experimental) | Sends to all subscribed plugins simultaneously, waits for the slowest to complete | `concurrent-events: true` (default) |

In concurrent mode, each plugin uses an independent callbackId, and a `CountDownLatch` waits for all plugins to complete. Event data shares the same snapshot across all plugins. Since game operations are serialized through the scheduler, no race conditions occur. The `cancelled` merge strategy: if any plugin cancels, the event is cancelled.

## Event Data

All event fields are primitive types (string/number/boolean/object). On the JS side, yeow-api's `adaptEvent()` automatically wraps them:
- `player` UUID → `new Player(uuid)` constructed directly (**zero round-trips**; `name` is lazily fetched synchronously and cached on first access).
- `from`/`to`/`respawnLocation` → `Location` objects.
- All fields are wrapped with getters/setters — handlers can **directly assign** (`e.deathMessage = ...`, etc.) and the assignment is recorded as a write-back mod (merged with the return value; direct assignment takes priority). `cancelled` can only cancel the event where it is exposed.

## Operations in Event Handlers

```js
// Auto mode (default)
eventOn('blockBreak', (e) => {
    // ✅ e.player is already a Player object (uuid directly constructed, zero round-trips; name lazily fetched on first access)
    const p = e.player;
    p.sendMessageSync('Block broken');

    // ✅ Cancel the event — takes effect immediately
    e.cancelled = true;

    // ✅ Async operation (event has been released, but async API calls are unaffected)
    post('player.sendMessage', {...});
});

// async handler — returning a Promise immediately completes
eventOn('blockBreak', async (e) => {
    e.cancelled = true;  // ✅ Takes effect in synchronous phase
    await someTask();    // Event has ended, setting values after this is ineffective
    e.cancelled = false; // ❌ Does not take effect
});

// Manual mode — full control over complete timing
eventOn('blockBreak', { manualRelease: true }, (e, complete) => {
    doAsyncCheck(result => {
        e.cancelled = result;
        complete({ cancelled: result });
    });
});
```

## Event Reentrant Deadlock

During event dispatch, the **event thread spin-waits for the JS handler to complete** while simultaneously **draining the scheduler queue** — this executes **synchronous tasks** (submitted via `call`) of the event plugin. If a synchronous task executing on the event thread **triggers a new event** that the plugin is listening to, an **event re-entry** occurs:

```
blockBreak event → event thread spin-waits
  → drains queue, picks up performCommandSync synchronous task → executes it on the spot
  → player executes command → triggers playerCommand event
  → waits for JS to handle playerCommand callback
  → but JS thread is blocked on performCommandSync's synchronous call (future.get)
  → deadlock until event timeout (default 5s)
```

**This occurs on both Paper and Folia** (Paper's main thread spin-draining `drainDuringWait` also executes synchronous tasks that trigger events). The timeout fallback ensures the server does not truly freeze, but the cost is: the game thread is blocked for 5s (Paper main thread / Folia's event region), Folia triggers watchdog warnings, and the event handler's actual result is delayed.

**Which synchronous operations trigger new events?** Synchronous command execution (`player.performCommandSync(...)` → `playerCommand`, `dispatchCommandSync(...)` → `serverCommand`), synchronous teleportation (`player.teleportSync(...)` → `playerTeleport`), and other mutation operations. **Asynchronous APIs do not cause reentrant deadlocks** — the danger lies in synchronous variants (`xxxSync`) and synchronous property writes.

> [!WARNING]
> **Unless the logic is very simple (pure reads, no event triggering), do not use synchronous operations in event handlers** — including:
> - `call(...)` / `xxxSync()` synchronous calls
> - **Property reads/writes** (`e.player.ping`, `player.health = x`, `world.time`, etc. — they are synchronous call sugar. Use async methods like `await player.setHealth()` instead)
> - Synchronous blocking calls (legacy `requestSync` and similar synchronous APIs)
>
> Use **asynchronous APIs** within events (`await xxx()`). Asynchronous operations do not block the JS thread, and events can complete normally.

## Event Handler Mode Selection

In auto mode, returning a Promise from an event handler immediately releases the event. This means setting `event.cancelled` after an `await` in an async handler is ineffective.

**But this does not mean async handlers have no value.** The vast majority of events are used to **trigger logic** rather than **modify results**:

```
eventOn('playerJoin', async (e) => {
    // Query database → send welcome message → log the event
    // These operations don't need to block the main thread waiting
    const msg = await db.getWelcome(e.player.uuid);
    e.player.sendMessage(msg);
    log.info(e.player.name + ' joined');
});
```

In the example above, the event does not need to be cancelled, nor does it need to return any mods. The async handler allows plugins to freely use async APIs **without blocking the main thread spin-wait**. This is the recommended approach:

| Scenario | Recommended Mode | Reason |
| -------- | ---------------- | ------ |
| Only need to trigger logic (send messages, modify data, log events) | Auto mode + async | Does not block the main thread, clean code |
| Need to synchronously determine the result (cancel, modify death message/MOTD, etc.) | Auto mode + synchronous handler | Directly assign event fields (or return mods), takes effect before `await` |
| Need to asynchronously fetch data before determining the result | Manual mode + `complete()` | User controls the timing of `$send('event.complete')` |

> **Rule**: If your event handler logic does not need to block the main thread waiting for results, boldly use async. The main thread cannot process ticks, AI, physics, etc. while spin-waiting for JS results, and prolonged spin-waits affect server performance.
