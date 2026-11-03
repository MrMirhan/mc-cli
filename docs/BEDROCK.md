# Bedrock Edition

`mccli bedrock` drives the real Bedrock client on Linux, as the Java mod drives the Java client. The Bedrock client cannot load a mod of ours, so mccli drives it from outside:

- It starts `mcpelauncher-client` ([minecraft-linux](https://minecraft-linux.github.io), GPL-3.0) from licensed game files.
- It finds the client's window, takes screenshots of it, and sends mouse and keys to it with xdotool.

## What you need

- **The launcher.** Install mcpelauncher (`mcpelauncher-manifest`, `mcpelauncher-ui-manifest` and `msa-manifest` from the minecraft-linux apt repository), `libatomic1`, xdotool, x11-utils (`xwininfo`) and ImageMagick (`import`).
- **A display.** An X display (`DISPLAY`, default `:1`): a desktop, Xvnc or Xvfb.
- **The game files, once.** The launcher downloads them for a Google account that owns Minecraft on Google Play. You also need its `mcpelauncher-updates` mod, which versions from 1.26 on need. After one start that you authorize, the client starts without the launcher and without a prompt.
- **A Microsoft account** is needed only for servers that ask for one (online-mode servers, featured servers): sign the bridge in once with `mccli bedrock login`. Servers that allow offline players need none.

Nothing secret is in the code. These paths are set at run time:

| Variable | What it is | Default |
| --- | --- | --- |
| `MCCLI_BEDROCK_GAME` | The launcher's data folder, with `versions/<version>/` and `mods/mcpelauncher-updates/<mod>/x86_64/` | `~/.local/share/mcpelauncher` |
| `MCCLI_BEDROCK_CREDENTIALS` | A folder laid out like a home folder, with the launcher's licence and sign-in files (`.local/share/mcpelauncher/pass.token`, `playapi_cache.dat`, `xal/`) and the game's first-run state (`games/com.mojang/ud*.dat`, `games/com.mojang/minecraftpe/options.txt`, `storage_object`, `clientId.txt`, `hs.hex`), copied from a client that ran once. `launch` copies each file into the home folder when it is missing there; a file already there stays, because the client renews it. Without the first-run state, a new home starts at the client's first-run screens. | none |
| `MCCLI_BEDROCK_STATE` | Where the running client's pid, log and screenshots go | `~/.cache/mccli-bedrock` |
| `MCCLI_BEDROCK_AUTH` | A folder, writable and kept between runs, where the bridge keeps its Xbox sign-in (`login`). With a sign-in there, the bridge joins servers as that account. | none |

Keep the credentials folder private (mode 0700). Never commit it.

## Commands

All of them print one JSON object: `{"success", "data", "error"}`.

| Command | What it does |
| --- | --- |
| `mccli bedrock launch [--version V] [--width 1280 --height 720] [--wait S]` | Starts the client (the newest version there are files for) and waits for its window (90 seconds). Closes the launcher's error popups, such as a missing sound device. If no window opens, it stops the client and says why: without its licence, the client opens no window. |
| `mccli bedrock status` | Whether it runs, its window and its place, and the versions there are. |
| `mccli bedrock screenshot [--path P]` | A PNG of the client's window, as the player sees it. |
| `mccli bedrock click X Y [--button left\|right] [--double]` | Clicks at X, Y of the window, in the screenshot's pixels. |
| `mccli bedrock key KEY...` | Presses keys by their xdotool names: `Escape`, `Return`, `w`, `ctrl+a`. |
| `mccli bedrock type TEXT` | Types text into the box in focus. |
| `mccli bedrock scroll up\|down [--amount N]` | Turns the mouse wheel. |
| `mccli bedrock chat MESSAGE` | Says a message in chat, or runs a `/command`, from the game view. |
| `mccli bedrock join-lan [--index N] [--wait S]` | From the title screen: Play, then the LAN world at that place (the bridge's). On the way it closes the client's sign-in and Marketplace offers, and takes the server's resource packs (Download Everything & Join). The client's first connection to a bridge sometimes stays at "Connecting to multiplayer game": after 12 seconds there, it cancels and joins again (twice at most; `retried` says how many times). Only at the default 1280 × 720 size. |
| `mccli bedrock logs [--lines N]` | The client's log. |
| `mccli bedrock quit` | Stops the client. |
| `mccli bedrock bridge --target HOST:PORT [--name NAME] [--offline]` | Runs the LAN bridge (below), until stopped. It joins the server as the signed-in Xbox account, or as an offline player with `--offline` or without a sign-in. |
| `mccli bedrock login` | Signs the bridge in to Xbox once: it prints a code to enter at microsoft.com/link, and waits. The sign-in stays in `MCCLI_BEDROCK_AUTH`; the bridge renews it. |

The client reads the keyboard once a frame, so mccli holds each key for a moment and moves the pointer before a click. With software rendering (llvmpipe, a few frames a second) a key can still be missed. After an action, check the result with a screenshot.

## Joining a server: the bridge

The Linux client (1.26) has two limits:

- It does not join a server by address without a Microsoft sign-in.
- Its network layer fails on direct connections: see minecraft-linux/mcpelauncher-manifest#2074.

It does join a **LAN world** over NetherNet. So `mccli bedrock bridge` does this:

- It advertises a LAN world on UDP 7551.
- It accepts the client over NetherNet (WebRTC).
- It relays the game to the server over RakNet, with [bedrock-protocol](https://github.com/PrismarineJS/bedrock-protocol)'s Relay.

The client joins the bridge offline. The bridge joins the server as the Xbox account signed in with `login`, so it joins any Bedrock server, featured servers too. Without a sign-in (or with `--offline`), it joins as an offline player: the server must allow offline players (`online-mode=false` on a dedicated server, or Geyser with `auth-type: offline` or Floodgate). An account plays in one place at a time: run one signed-in bridge per account. If a sign-in is gone, the bridge writes a new code to its log and turns the player away.

```sh
cd bedrock && npm ci          # once; raknet-native may need cmake and a C++ compiler to build
mccli bedrock bridge --target geyser.test.local:19132 --name "Test server"
```

Run the bridge **on another host or container than the client**, on the network of the client's default route:

- The client broadcasts its LAN discovery on that network only.
- A bridge on the client's own address gets the connection, but no game traffic.

On Docker, put the client and the bridge in the same user-defined bridge network, with inter-container traffic on.

Checked with Minecraft 1.26.60.4 (launcher 1.8.7) and a dedicated server 1.26.61. The client joined through the bridge, with no Microsoft sign-in, and the server saw it spawn. With a required resource pack on the server, the client downloaded it: its texture and a JSON UI label it adds to the HUD showed in the screenshot.
