# Emage 🎨

A highly-optimized image and GIF renderer built for modern Paper servers. By completely bypassing Minecraft’s heavy default map API, Emage uses a custom packet pipeline to stream media directly to your players. You get the freedom to decorate your world with smooth animations without compromising server performance.

[![Modrinth](https://img.shields.io/modrinth/dt/emage?logo=modrinth&label=Modrinth&color=00AF5C)](https://modrinth.com/plugin/emage)
[![SpigotMC](https://img.shields.io/badge/SpigotMC-Download-orange)](https://www.spigotmc.org/resources/emage.130410/)

## Requirements
* **Java 22**
* **Paper 1.21** or higher
* **[PacketEvents](https://modrinth.com/plugin/packetevents)** (Required dependency)

## Installation
1. Place the `PacketEvents.jar` and `Emage-VERSION.jar` into your `plugins/` folder.
2. Start the server.
3. Edit `plugins/Emage/config.yml` to adjust various limits or customize messages.

## Usage
To display an image, place Item Frames on a wall in a rectangular grid. Look at one of the frames and run the apply command.

### Commands
| Command                                    | Description                                            |
|--------------------------------------------|--------------------------------------------------------|
| `/emage apply-grid <columns> <rows> <url>` | Manually specify the grid size to apply the image/GIF. |
| `/emage apply <url>`                       | Auto-detects the frame grid and applies the image/GIF. |
| `/emage remove`                            | Remove the grid and its files.                         |
| `/emage reload`                            | Reload the configuration.                              |

## Building
```bash
mvn clean package
```
The compiled jar will be output to the `target/` directory.
