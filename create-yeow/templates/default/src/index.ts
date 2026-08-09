import {
  onInit, onLoad, onUnload, registerCommand, eventOn,
  Location, pdcSet, pdcGet, log,
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
    permission: { node: 'back.use', default: 'all' },   // 声明权限节点：普通玩家默认可用，服主可经权限插件管理
    executor: async (p) => {
      if (p.sender === 'CONSOLE') return;
      const player = p.sender;                           // 已确认非 CONSOLE → Player
      const raw = await pdcGet(player.uuid, 'back.deathLocation');
      if (!raw) return player.sendMessage('<red>No death location recorded</red>');

      const loc = JSON.parse(raw);
      await player.teleport(new Location(loc.x, loc.y, loc.z, 0, 0, loc.world));
      await player.sendMessage('<green>Teleported to death location</green>');
    },
  });

  // ── /ping ──
  registerCommand('ping', {
    permission: { node: 'ping.use', default: 'all' },
    executor: async (p) => {
      if (p.sender === 'CONSOLE') { log.info('Ping: console'); return; }
      const player = p.sender;                           // Player
      await player.sendMessage(`Ping: ${player.ping}ms`);
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
