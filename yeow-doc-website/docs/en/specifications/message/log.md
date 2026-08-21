# Log Channel

The underlying implementation of `console.log` / `console.warn` / `console.error` / `console.info`.

## Call Format

```json
{ "level": "<level>", "message": "<text>" }
```

`level` can be: `"INFO"`, `"WARN"`, `"ERROR"`.

The implementation should output the message to the server at the corresponding log level. The `message` field already contains the final message text — the `[pluginName]` prefix is added by the `console.*` wrapper in init.js, so the implementation should simply output the message directly and must not add a prefix.
