# Emage 🎨

**Emage** is a high-performance Minecraft plugin that displays images and animated GIFs on item frames. It features automatic grid detection, high-quality dithering, advanced compression, and smooth animation playback.

[![Modrinth](https://img.shields.io/modrinth/dt/emage?logo=modrinth&label=Modrinth&color=00AF5C)](https://modrinth.com/plugin/emage)
[![SpigotMC](https://img.shields.io/badge/SpigotMC-Download-orange)](https://www.spigotmc.org/resources/emage.130410/)
[![GitHub](https://img.shields.io/github/v/release/EdithyLikesToCode/Emage?logo=github&label=GitHub)](https://github.com/EdithyLikesToCode/Emage)

---

> ## ⚠️ GIF Support - Experimental
>
> **Animated GIF support is experimental and may cause performance issues!**
>
> ### Known Limitations
> | Issue | Description |
> |:------|:------------|
> | **Grid Size** | Maximum 4x4 for GIFs (3x3 or smaller recommended) |
> | **Processing Time** | Large GIFs may take 5-15 seconds to process |
> | **Network Usage** | Each animated map sends ~16KB per frame update to nearby players |
> | **CPU Usage** | Multiple animated GIFs will increase server CPU usage |
> | **Player Lag** | Players with slow connections may experience timeout issues with large GIF grids |
>
> ### Recommendations
> | Do ✅ | Don't ❌ |
> |:------|:---------|
> | Use **1x1 or 2x2** grids for GIFs | Avoid 4x4 GIF grids unless necessary |
> | Keep GIF frame count under **50 frames** | Don't place multiple large GIF displays near each other |
> | Use `--fast` quality for better performance | Don't use `--high` quality for large GIFs |
> | Limit the number of active GIFs on your server | Don't ignore lag warnings |
>
> **Static images (PNG, JPG, WebP) are fully stable and recommended for large displays.**

---

## ✨ Features

### Image Display
- 🖼️ **Static Images** - Display any PNG, JPG, or WebP image from the web
- 🎬 **Animated GIFs** - GIF support with smooth playback and frame synchronization *(⚠️ EXPERIMENTAL)*
- 🧩 **Grid Support** - Automatically detects connected item frames (up to 10x10 for images, 4x4 for GIFs)
- 📐 **Auto-Scaling** - Automatically fills detected frame grid or specify custom dimensions

### Quality & Processing
- 🎨 **Advanced Dithering** - Floyd-Steinberg dithering with edge-aware error diffusion
- 🔍 **Smart Sharpening** - Unsharp mask sharpening to counteract resize blur
- 🖌️ **Edge Enhancement** - Laplacian edge enhancement for crisp details on maps
- 📊 **Three Quality Modes** - Fast, Balanced, and High quality options

### Performance
- 🚀 **Zero Lag** - All image processing happens asynchronously on worker threads
- ⚡ **Distance-Based Updates** - Only updates animations for nearby players
- 🎯 **Adaptive Performance** - Automatically adjusts quality based on server load
- 💾 **GIF Caching** - Processed GIFs are cached to avoid reprocessing
- 🔄 **SyncGroup System** - Entire grids share single frame calculation
- 🧠 **Memory Pooling** - Reuses buffers to reduce garbage collection

### Storage & Persistence
- 💾 **Persistent Storage** - Images survive server restarts
- 📦 **Advanced Compression** - Up to 90% smaller files with palette reduction and delta encoding
- 🗂️ **Grid Files** - Multiple maps stored in single compressed files
- 🧹 **Cleanup Tools** - Automatically remove unused map files

---

## 📸 Screenshots

| Normal Image | Large Image |
|:---:|:---:|
| ![Static](https://cdn.imgchest.com/files/1047bf5e0c63.png) | ![Grid](https://cdn.imgchest.com/files/554f1415a4fd.png) |

---

## 📋 Commands

| Command | Description | Permission |
|:--------|:------------|:-----------|
| `/emage <url> [size] [quality]` | Upload an image onto item frames | `emage.use` |
| `/emage help` | Show information about the plugin | `emage.use` |
| `/emage reload` | Reload the plugin | `emage.admin` |
| `/emage cleanup` | Remove unused map files | `emage.admin` |
| `/emage stats` | View storage statistics | `emage.admin` |
| `/emage perf` | View performance statistics | `emage.admin` |
| `/emage cache` | View GIF cache statistics | `emage.admin` |
| `/emage clearcache` | Clear the GIF cache | `emage.admin` |
| `/emage migrate` | Convert legacy format files | `emage.admin` |
| `/emage update` | Check for updates | `emage.admin` |

---

## 🎛️ Flags

| Flag | Aliases | Description |
|:-----|:--------|:------------|
| `--fast` | `-f`, `--low`, `--speed` | Fastest processing, lower quality |
| `--balanced` | `-b`, `--normal` | Default quality (recommended) |
| `--high` | `-h`, `--hq`, `--quality` | Best quality, slower processing |
| `--nocache` | `--nc`, `--fresh` | Force reprocess GIF (ignore cache) |

---

## 📏 Grid Size Limits

| Content Type | Maximum Size | Recommended |
|:-------------|:-------------|:------------|
| Static Images | 10x10 (100 maps) | Any size |
| Animated GIFs | 4x4 (16 maps) | 2x2 or smaller |

---

## 📥 Installation

1. Download the latest release from [Modrinth](https://modrinth.com/plugin/emage), [SpigotMC](https://www.spigotmc.org/resources/emage.130410/), or [GitHub](https://github.com/EdithyLikesToCode/Emage/releases)
2. Place `Emage.jar` in your server's `plugins` folder
3. Restart your server
4. (Optional) Configure settings in `plugins/Emage/config.yml`

---

## 🎮 Usage

### Basic Usage

1. Place item frames on a wall in a grid pattern
2. Look at one of the item frames
3. Run `/emage <image-url>`

### Examples

```bash
# Auto-detect grid size
/emage https://example.com/image.png

# Force specific grid size
/emage https://example.com/image.png 3x3

# Display animated GIF (keep it small!)
/emage https://example.com/animation.gif 2x2

# High quality static image
/emage https://example.com/photo.jpg --high

# Fast GIF processing (recommended for GIFs)
/emage https://example.com/animation.gif 2x2 --fast

# Force reprocess a cached GIF
/emage https://example.com/animation.gif --nocache
```

### 🐛 Reporting Issues
Found a bug? Please report it on [GitHub Issues](https://github.com/EdithyLikesToCode/Emage/issues/new/choose) with:

- Server version (e.g., Paper 1.20.6)
- Emage version (/emage update shows current version)
- Steps to reproduce
- Error logs from logs/latest.log (if any)
- Screenshots (if visual issue)

### 💖 Support
If you find Emage useful, consider:

- ⭐ Starring the repository on [GitHub](https://github.com/EdithyLikesToCode/Emage)
- 📝 Leaving a review on [SpigotMC](https://www.spigotmc.org/resources/emage.130410/) or [Modrinth](https://modrinth.com/plugin/emage)
- 🐛 Reporting bugs and suggesting features
- 💬 Sharing with other server owners