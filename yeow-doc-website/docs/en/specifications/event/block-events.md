# Block Events

## `blockBreak`

| Field  | Type          | Description                        |
|--------|---------------|------------------------------------|
| `player` | string (UUID) | The player who broke the block     |
| `block` | string        | The material key of the broken block |
| `x`     | number        | X coordinate                       |
| `y`     | number        | Y coordinate                       |
| `z`     | number        | Z coordinate                       |

## `blockPlace`

| Field        | Type          | Description                             |
|--------------|---------------|-----------------------------------------|
| `player`     | string (UUID) | The player who placed the block         |
| `block`      | string        | The material key of the placed block    |
| `blockAgainst` | string      | The material key of the block the block was placed against |
| `x`          | number        | X coordinate                            |
| `y`          | number        | Y coordinate                            |
| `z`          | number        | Z coordinate                            |

## `blockFade`

| Field  | Type   | Description                           |
|--------|--------|---------------------------------------|
| `block` | string | The material key of the faded block   |
| `x`     | number | X coordinate                          |
| `y`     | number | Y coordinate                          |
| `z`     | number | Z coordinate                          |

## `blockGrow`

| Field  | Type   | Description                           |
|--------|--------|---------------------------------------|
| `block` | string | The material key of the grown block   |
| `x`     | number | X coordinate                          |
| `y`     | number | Y coordinate                          |
| `z`     | number | Z coordinate                          |

## `blockSpread`

| Field  | Type   | Description                            |
|--------|--------|----------------------------------------|
| `block` | string | The material key of the spread block   |
| `x`     | number | X coordinate                           |
| `y`     | number | Y coordinate                           |
| `z`     | number | Z coordinate                           |

## `blockExplode`

| Field  | Type   | Description                            |
|--------|--------|----------------------------------------|
| `block` | string | The material key of the exploded block |
| `x`     | number | X coordinate                           |
| `y`     | number | Y coordinate                           |
| `z`     | number | Z coordinate                           |
