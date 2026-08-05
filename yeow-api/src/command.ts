import { call } from './task.js';
import type { TaskOptions } from './task.js';

export interface CommandSender {
  readonly name: string;
  readonly uuid: string;
  readonly isPlayer: boolean;
  sendMessage(msg: string): void;
}

export interface CommandPayload {
  readonly sender: CommandSender;
  readonly args: string[];
  readonly label: string;
}

type CompleterFn = (sender: CommandSender, args: string[]) => string[] | Promise<string[]>;
export interface ManualCompleter {
  manualRelease: true;
  handler: (sender: CommandSender, args: string[], complete: (result: string[]) => void) => void;
}

export interface CommandOptions {
  description?: string;
  usage?: string;
  permission?: string;
  aliases?: string[];
  executor: (payload: CommandPayload) => void;
  completer?: CompleterFn | ManualCompleter;
}

export function registerCommand(name: string, options: CommandOptions, taskOptions?: TaskOptions): boolean {
  const executor = options.executor;
  const completer = options.completer;
  const pluginName = __plugin?.name || 'unknown';

  function decorateSender(sender: CommandSender): CommandSender {
    if (sender?.uuid && sender.isPlayer) {
      (sender as any).sendMessage = (msg: string) => {
        call('player.sendMessage', { uuid: sender.uuid, message: msg }, taskOptions);
      };
    } else {
      (sender as any).sendMessage = (msg: string) => {
        $send('log', { level: 'INFO', message: msg });
      };
    }
    return sender;
  }

  const cbId = _registerCallback((payload: CommandPayload) => {
    (payload as any).sender = decorateSender(payload.sender);
    executor(payload);
  }, { persistent: true });

  let compCbId = '';
  if (completer) {
    let manualRelease = false;
    let completerFn: Function;
    if (typeof completer === 'object' && (completer as any).manualRelease) {
      manualRelease = true;
      completerFn = (completer as any).handler;
    } else {
      completerFn = completer as CompleterFn;
    }

    compCbId = _registerCallback((data: any) => {
      try {
        const sender = decorateSender(data.sender);
        if (manualRelease) {
          const complete = (result: string[]) => {
            $send('task', {
              type: 'command.tabComplete',
              params: { callbackId: compCbId, completions: result },
              cb: '',
            });
          };
          completerFn(sender, data.args, complete);
        } else {
          const result = completerFn(sender, data.args);
          if (result && typeof result.then === 'function') {
            $send('task', {
              type: 'command.tabComplete',
              params: { callbackId: compCbId, completions: [] },
              cb: '',
            });
          } else {
            $send('task', {
              type: 'command.tabComplete',
              params: { callbackId: compCbId, completions: result || [] },
              cb: '',
            });
          }
        }
      } catch (_e) {
        $send('task', {
          type: 'command.tabComplete',
          params: { callbackId: compCbId, completions: [] },
          cb: '',
        });
      }
    }, { persistent: true });
  }

  return call<boolean>('command.register', {
    pluginName,
    commandName: name,
    callbackId: String(cbId),
    completerCbId: compCbId,
    description: options.description,
    usage: options.usage,
    permission: options.permission,
    aliases: options.aliases,
  }, taskOptions);
}
