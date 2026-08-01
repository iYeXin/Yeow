import {
  onInit, onLoad, onUnload, registerCommand, eventOn,
  Player, Location, pdcSet, pdcGet, log,
} from 'yeow-api';
import type { PlayerDeathEvent } from 'yeow-api';

onInit(() => { log.info('Init'); });
onLoad(() => { log.info('Ready'); });

onLoad(() => {
  // ── /back: 返回死亡位置 ──
  eventOn('playerDeath', async (e: PlayerDeathEvent) => {
    const loc = e.player.location;
    if (!loc) return;
    const data = JSON.stringify({ x: loc.x, y: loc.y, z: loc.z, world: loc.world || e.player.world });
    pdcSet(e.player.uuid, 'back.deathLocation', data);
    await e.player.sendMessage(
      '<red>You died!</red> <gray>Use</gray> <click:run_command:/back><aqua><u>/back</u></aqua></click> <gray>to return</gray>',
    );
  });

  registerCommand('back', {
    description: 'Teleport to your death location',
    executor: async (p) => {
      const raw = await pdcGet(p.sender.uuid, 'back.deathLocation');
      if (!raw) return p.sender.sendMessage('<red>No death location recorded</red>');

      const loc = JSON.parse(raw);
      const player = await Player.get(p.sender.uuid);
      if (!player) return;
      await player.teleport(new Location(loc.x, loc.y, loc.z, 0, 0, loc.world));
      p.sender.sendMessage('<green>Teleported to death location</green>');
    },
  });

  // ── /ping ──
  registerCommand('ping', {
    executor: async (p) => {
      const player = await Player.get(p.sender.uuid);
      if (player) p.sender.sendMessage(`Ping: ${player.ping}ms`);
    },
  });

  // ── 事件 ──
  eventOn('playerJoin', (e) => {
    log.info(`${e.player.name} joined`);
  });

  eventOn('blockBreak', (e) => {
    if (e.block === 'minecraft:bedrock') e.cancelled = true;
  });
});

onUnload(() => {
  log.info('Unloaded');
});
