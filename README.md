# FraXy Timelapse

Server-side Forge 1.20.1 mod for detecting block changes inside configured camera zones and triggering a secure webhook when a zone reaches a configurable change threshold.

## What it does

- Server-side only; clients do not need the mod.
- Four pre-created camera slots (`camera1`–`camera4`).
- Per-camera enabled flag and cuboid watch area.
- Default trigger threshold: 5 block events.
- Default cooldown: 600 seconds (10 minutes) per camera.
- Sends an HTTP POST to the configured webhook with a bearer token.
- Designed to trigger the Cloudflare Worker that runs Browser Run and stores screenshots in R2.

## Configuration

After the first server start, edit:

`config/fraxy-timelapse.json`

Example:

```json
{
  "webhookUrl": "https://fraxy-dynmap.fraxy.workers.dev/trigger",
  "webhookToken": "CHANGE_ME",
  "changeThreshold": 5,
  "cooldownSeconds": 600,
  "cameras": {
    "camera1": {
      "id": "camera1",
      "enabled": true,
      "world": "minecraft:overworld",
      "minX": 700,
      "minY": 0,
      "minZ": -100,
      "maxX": 1100,
      "maxY": 200,
      "maxZ": 300
    }
  }
}
```

A block event inside an enabled camera zone increments that camera's counter. Once the threshold is reached and the camera is outside its cooldown window, the mod sends:

```json
{
  "camera": "camera1",
  "changes": 5,
  "timestamp": 1760000000000
}
```

with:

```http
Authorization: Bearer YOUR_TOKEN
Content-Type: application/json
```

## Important behavior

The counter resets when a trigger is emitted. Changes occurring during the cooldown are still counted toward the next trigger.

The current implementation listens to Forge `BlockEvent`s, so block place/break/related block events inside the configured cuboids are used as the change signal. It intentionally does not poll the world or call Browser Run itself.

## Build

This project targets Minecraft 1.20.1 / Forge 47.4.23 / Java 17.

```bash
./gradlew build
```

The jar is produced under `build/libs/`.
