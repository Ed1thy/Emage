# Emage 🎨

A Minecraft plugin for displaying images and animated GIFs on item frames. Supports grid detection, dithering and compression.

[![Modrinth](https://img.shields.io/modrinth/dt/emage?logo=modrinth&label=Modrinth&color=00AF5C)](https://modrinth.com/plugin/emage)
[![SpigotMC](https://img.shields.io/badge/SpigotMC-Download-orange)](https://www.spigotmc.org/resources/emage.130410/)
[![GitHub](https://img.shields.io/github/v/release/EdithyLikesToCode/Emage?logo=github&label=GitHub)](https://github.com/EdithyLikesToCode/Emage)

> ⚠️ **Remember, this is Minecraft.** The color palette is limited to the map color table, and GIF playback is bound by server tick rate and packet throughput. The color accuracy and animation performance doesn't get much better than this. it's about as good as it gets within Minecraft's constraints.

## Features

- **Static images** - PNG, JPG, and any format supported by Java's ImageIO. Rendered with Floyd-Steinberg dithering by default.
- **Animated GIFs** - Full GIF support including per-frame delays, disposal methods, and frame positioning. Experimental.
- **Grid detection** - BFS-based. Works on all six block faces (walls, floors, ceilings). Up to 10x10 for images, 4x4 for GIFs (configurable).
- **Three quality modes** - Fast (ordered/Bayer), Balanced (Floyd-Steinberg), High (Jarvis-Judice-Ninke). All gamma-corrected.
- **Async processing** - Image downloads, resizing, and dithering run on a dedicated thread pool. The server thread only handles the final map application.
- **Adaptive performance** - The plugin monitors player count, active map count, and memory pressure, then adjusts animation FPS, render distance, and update intervals automatically.
- **Distance culling** - Animation packets are only sent to players within render distance. Per-player packet budgets prevent network saturation.
- **GIF caching** - Processed GIF data is kept in memory (LRU, 30 minute expiry, 100MB cap) so re-applying the same GIF doesn't require reprocessing.
- **Memory pooling** - Map byte buffers (16KB each) are pooled and reused to reduce GC pressure.
- **Persistent storage** - All rendered maps survive restarts. Grids are stored as single compressed files rather than one file per map.
- **Cleanup tools** - Scans item frames and player inventories to find which maps are still in use, then deletes orphaned files.
- **SSRF protection** - Blocks requests to loopback, link-local, and private addresses. Validates URL schemes, enforces redirect limits, and caps download size at 50MB.

---

## Screenshots

| Single frame | Multi-frame grid |
|:---:|:---:|
| ![Static](https://cdn.imgchest.com/files/1047bf5e0c63.png) | ![Grid](https://cdn.imgchest.com/files/554f1415a4fd.png) |

## Commands

| Command | Description | Permission |
|:--------|:------------|:-----------|
| `/emage <url> [size] [flags]` | Render an image onto item frames | `emage.use` |
| `/emage help` | Show command reference | `emage.use` |
| `/emage reload` | Reload config | `emage.admin` |
| `/emage cleanup` | Delete unused map files | `emage.admin` |
| `/emage stats` | Show storage stats | `emage.admin` |
| `/emage perf` | Show performance stats | `emage.admin` |
| `/emage cache` | Show GIF cache stats | `emage.admin` |
| `/emage clearcache` | Clear the GIF cache | `emage.admin` |
| `/emage update` | Check for updates | `emage.admin` |

## Flags

| Flag | Aliases | Effect |
|:-----|:--------|:-------|
| `--fast` | `-f`, `--low`, `--speed` | Ordered dithering. Fastest, lowest quality. |
| `--balanced` | `-b`, `--normal` | Floyd-Steinberg. Default. |
| `--high` | `-h`, `--hq`, `--quality` | Jarvis-Judice-Ninke. Slowest, best quality. |
| `--nocache` | `--nc`, `--fresh` | Ignore cached GIF data and reprocess from scratch. |

---

## Installation

1. Download from [Modrinth](https://modrinth.com/plugin/emage), [SpigotMC](https://www.spigotmc.org/resources/emage.130410/), or [GitHub Releases](https://github.com/EdithyLikesToCode/Emage/releases).
2. Drop `Emage.jar` into your `plugins` folder.
3. Restart the server. The first startup takes a moment while the color lookup cache is built.
4. Config is at `plugins/Emage/config.yml` if you want to adjust limits or performance settings.

## Usage

Place item frames on a wall (or floor/ceiling) in a grid, look at one of them, and run the command.

```
/emage https://example.com/photo.png
/emage https://example.com/photo.png 3x3
/emage https://example.com/photo.png --high
/emage https://example.com/animation.gif 2x2 --fast
/emage https://example.com/animation.gif --nocache
```

If you don't specify a size, the plugin uses whatever grid it detects. If you do specify a size, it anchors from the frame you're looking at.

---

## Reporting Issues

If you find a bug, open an issue on [GitHub](https://github.com/EdithyLikesToCode/Emage/issues/new/choose) with:

- Server software and version (e.g. Paper 1.20.6)
- Emage version (`/emage update` shows it)
- Steps to reproduce
- Relevant logs from `logs/latest.log`
- Screenshots if it's a visual problem

## Links

- [GitHub](https://github.com/EdithyLikesToCode/Emage)
- [Modrinth](https://modrinth.com/plugin/emage)
- [SpigotMC](https://www.spigotmc.org/resources/emage.130410/)
