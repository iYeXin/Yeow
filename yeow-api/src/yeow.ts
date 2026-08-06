/**
 * Yeow —— 聚合全部 API 的大对象（默认导出）。
 *
 * ⚠ 不推荐使用：默认导入本对象会把整个 yeow-api 打进 bundle，破坏 tree-shaking，
 * 显著增大插件体积。请始终使用按需命名导入（如 `import { Player } from 'yeow-api'`）。
 */
import { call, post } from './task.js';
import { Player } from './player.js';
import { World } from './world.js';
import { Entity, LivingEntity } from './entity.js';
import { Block } from './block.js';
import { Location } from './location.js';
import { eventOn, eventOff } from './event.js';
import { registerCommand } from './command.js';
import { onInit, onLoad, onUnload } from './lifecycle.js';
import * as fsModule from './fs.js';
import * as assetsModule from './assets.js';
import { listen, respond, close, request, requestSync } from './http.js';
import { registerService, registerNativeService, request as serviceRequest, subscribe as serviceSubscribe, publish as servicePublish } from './service.js';
import * as serverModule from './server.js';
import { log, Logger } from './log.js';
import { path } from './path.js';
import { Chunk, ChunkSnapshot, ChunkTopSnapshot } from './chunk.js';
import * as bossbarModule from './bossbar.js';
import { createGUI, open as openGUI, destroy as destroyGUI, close as closeGUI, setItem as setGUIItem, fill as fillGUI, clear as clearGUI } from './gui.js';
import * as inventoryModule from './inventory.js';
import * as scoreboardModule from './scoreboard.js';
import * as advancementModule from './advancement.js';
import { add as addRecipe, remove as removeRecipe, getForItem as getRecipesForItem } from './recipe.js';
import * as potionModule from './potion.js';
import { playSound, stopSound, stopAllSounds } from './sound.js';
import { spawnParticle } from './particle.js';
import * as pdcModule from './pdc.js';
import { getMaterials, getBlocks, getItems } from './material.js';
import { InstanceId, GUIHandle, BossBarHandle, InventoryHandle } from './instance-id.js';

const Yeow = {
  Player, World, Entity, LivingEntity, Block, Location,
  Chunk, ChunkSnapshot, ChunkTopSnapshot,
  eventOn, eventOff,
  registerCommand,
  onInit, onLoad, onUnload,
  call, post,
  ...fsModule,
  ...assetsModule,
  listen, respond, close, request, requestSync,
  registerService, registerNativeService, serviceRequest, serviceSubscribe, servicePublish,
  ...serverModule,
  log, Logger, path,
  ...bossbarModule,
  createGUI, openGUI, destroyGUI, closeGUI, setGUIItem, fillGUI, clearGUI,
  ...inventoryModule,
  ...scoreboardModule,
  ...advancementModule,
  addRecipe, removeRecipe, getRecipesForItem,
  ...potionModule,
  playSound, stopSound, stopAllSounds,
  spawnParticle,
  ...pdcModule,
  getMaterials, getBlocks, getItems,
  InstanceId, GUIHandle, BossBarHandle, InventoryHandle,
};

export default Yeow;
