# Emage 🎨

A Minecraft plugin that lets you display images and GIFs on item frames. Point at a frame, run a command with a URL, and the image gets rendered onto the map. Works with grids of item frames for larger displays.

[![Modrinth](https://img.shields.io/modrinth/dt/emage?logo=modrinth&label=Modrinth&color=00AF5C)](https://modrinth.com/plugin/emage)
[![SpigotMC](https://img.shields.io/badge/SpigotMC-Download-orange)](https://www.spigotmc.org/resources/emage.130410/)
[![GitHub](https://img.shields.io/github/v/release/Ed1thy/Emage?logo=github&label=GitHub)](https://github.com/Ed1thy/Emage)

---

### 🎉 GIFs are now fully supported!

GIF animations have been completely reworked. They now run on a dedicated render thread with delta encoding, meaning you can have hundreds of GIFs on your server with little to no impact on performance.

---

## Features

- **Static images** - Download and render any image onto item frames. Supports grids up to 16×16 by default (configurable).
- **Animated GIFs** - GIFs play back as animations on item frames, up to 5×5 grids by default (configurable).
- **Automatic grid detection** - Place item frames in a rectangle, look at one, and the plugin figures out the full grid layout. You can also specify a size manually.
- **Persistent storage** - Images and GIFs survive server restarts. Data is stored in a local SQLite database with Zstd compression.
- **Adaptive performance** - The plugin adjusts animation FPS, render distance, and update rates based on player count, active maps, and memory usage. Can be disabled.
- **GIF caching** - Processed GIFs are cached in memory so re-applying the same GIF is instant.
- **Configurable** - Rate limits, download limits, grid size caps, memory ceilings, and all messages are configurable.

## Requirements

- Java 17+
- Spigot or Paper 1.18+
- [PacketEvents](https://github.com/retrooper/packetevents)

## Installation

1. Install [PacketEvents](https://modrinth.com/plugin/packetevents) if you haven't already.
2. Drop the Emage jar into your `plugins/` folder.
3. Start the server. On first run, the color palette cache will be built - this takes a few seconds.
4. Edit `plugins/Emage/config.yml` if you want to change defaults.

## Usage

1. Place item frames on a wall in a grid pattern (e.g., 3×3).
2. Look at one of the frames.
3. Run `/emage <url>` or `/emage <url> 3x3` to specify a size.

The plugin detects the connected frames, downloads the image, dithers it to Minecraft's map color palette, and applies it.

For GIFs, append `-nocache` to force reprocessing instead of using a cached version.

```
/emage https://example.com/image.png
/emage https://example.com/image.png 3x3
/emage https://example.com/animation.gif 2x2 -nocache
```

Breaking an item frame that has an Emage map on it will clean up the associated data and recycle the map ID.

## Commands

| Command | Description | Permission |
|--|--|--|
| `/emage <url> [WxH]` | Apply an image or GIF to the targeted item frame grid | `emage.use` |
| `/emage help` | Show command help | `emage.use` |
| `/emage reload` | Reload the config | `emage.admin` |
| `/emage stats` | Show storage statistics | `emage.admin` |
| `/emage perf` | Show performance info (FPS, render distance, cache) | `emage.admin` |
| `/emage cache` | Show GIF cache statistics | `emage.admin` |
| `/emage clearcache` | Clear the GIF processing cache | `emage.admin` |
| `/emage cleanup` | Scan for and delete unused map data | `emage.admin` |
| `/emage update` | Check for a new version | `emage.admin` |
| `/emage synccolors` | Re-sync the map color palette with the server and rebuild the cache | `emage.admin` |

## Permissions

| Permission | Default | Description |
|--|--|--|
| `emage.use` | Everyone | Use `/emage` to apply images |
| `emage.admin` | OP | Access admin subcommands and receive update notifications on join |

## Configuration

The config file (`config.yml`) covers:

- **Performance** - Max/min FPS for GIF animations (default 20–60), render distance (default 32 blocks), max packets per player per tick, and adaptive scaling toggle.
- **Quality** - Max GIF frames (240), max grid size for GIFs (5×5) and static images (16×16).
- **Memory** - Soft memory ceiling for adaptive performance scaling (256 MB default).
- **Downloads** - Max file size (50 MB), connection/read timeouts, max redirects, and an option to block downloads from internal/local network addresses (enabled by default).
- **Cache** - Max cached GIFs (20), max cache memory (100 MB), and expiry time (30 minutes).
- **Rate limits** - Cooldown between commands per player (5 seconds) and max concurrent processing tasks server-wide (3).
- **Messages** - Every player-facing message is customizable with hex color support (`&#RRGGBB`).

All values have sensible defaults. Most servers won't need to change anything.

## How it works (briefly)

Images are downloaded, scaled to fit the item frame grid, and dithered using a Jarvis error-diffusion algorithm in linear color space. Color matching uses CIEDE2000 against Minecraft's ~208 map palette colors, with a precomputed lookup table for speed.

GIF frames are decoded with proper disposal method handling, dithered with inter-frame stability (unchanged regions reuse previous results), and sent to players as map packets via PacketEvents. Only pixels that changed between frames are transmitted (delta encoding), and packets are only sent to players within render distance who are looking toward the frames. Animation ticking runs on a separate thread and adapts to server load, so GIF playback has little to no effect on main-thread performance.

Map data is stored in SQLite with Zstd compression. Legacy file-based storage (maps/ folder with .emap files) is automatically migrated on first run.

## Building

```bash
mvn clean package
```

Requires Java 17. The output jar will be in `target/`.