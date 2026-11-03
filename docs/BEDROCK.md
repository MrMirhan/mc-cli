# Bedrock Edition

`mccli bedrock` drives the real Bedrock client on Linux, as the Java mod drives the Java client. The Bedrock client cannot load a mod of ours, so mccli drives it from outside:

- It starts `mcpelauncher-client` ([minecraft-linux](https://minecraft-linux.github.io), GPL-3.0) from licensed game files.
- It finds the client's window, takes screenshots of it, and sends mouse and keys to it with xdotool.

## What you need

- **The launcher.** Install mcpelauncher (`mcpelauncher-manifest`, `mcpelauncher-ui-manifest` and `msa-manifest` from the minecraft-linux apt repository), `libatomic1`, xdotool, x11-utils (`xwininfo`) and ImageMagick (`import`).
- **A display.** An X display (`DISPLAY`, default `:1`): a desktop, Xvnc or Xvfb.
- **The game files, once.** The launcher downloads them for a Google account that owns Minecraft on Google Play. You also need its `mcpelauncher-updates` mod, which versions from 1.26 on need. After one start that you authorize, the client starts without the launcher and without a prompt.
- **A Microsoft account is optional.** The client always joins the bridge offline; the bridge reaches the server offline, or signed in with a Microsoft account for online servers (below).

Nothing secret is in the code. These paths are set at run time:

| Variable | What it is | Default |
| --- | --- | --- |
| `MCCLI_BEDROCK_GAME` | The launcher's data folder, with `versions/<version>/` and `mods/mcpelauncher-updates/<mod>/x86_64/` | `~/.local/share/mcpelauncher` |
| `MCCLI_BEDROCK_CREDENTIALS` | A folder laid out like a home folder, with the launcher's licence and sign-in files (`.local/share/mcpelauncher/pass.token`, `playapi_cache.dat`, `xal/`). `launch` copies each file into the home folder when it is missing there; a file already there stays, because the client renews it. | none |
| `MCCLI_BEDROCK_STATE` | Where the running client's pid, log and screenshots go | `~/.cache/mccli-bedrock` |

Keep the credentials folder private (mode 0700). Never commit it.

## Commands

All of them print one JSON object: `{"success", "data", "error"}`.

| Command | What it does |
| --- | --- |
| `mccli bedrock launch [--version V] [--width 1280 --height 720]` | Starts the client (the newest version there are files for) and waits for its window. Closes the launcher's error popups, such as a missing sound device. |
| `mccli bedrock status` | Whether it runs, its window and its place, and the versions there are. |
| `mccli bedrock screenshot [--path P]` | A PNG of the client's window, as the player sees it. |
| `mccli bedrock click X Y [--button left\|right] [--double]` | Clicks at X, Y of the window, in the screenshot's pixels. |
| `mccli bedrock key KEY...` | Presses keys by their xdotool names: `Escape`, `Return`, `w`, `ctrl+a`. |
| `mccli bedrock type TEXT` | Types text into the box in focus. |
| `mccli bedrock scroll up\|down [--amount N]` | Turns the mouse wheel. |
| `mccli bedrock chat MESSAGE` | Says a message in chat, or runs a `/command`, from the game view. |
| `mccli bedrock join-lan [--index N]` | From the title screen: Play, then the LAN world at that place (the bridge's). Only at the default 1280 × 720 size. |
| `mccli bedrock logs [--lines N]` | The client's log. |
| `mccli bedrock quit` | Stops the client. |
| `mccli bedrock bridge --target HOST:PORT [--name NAME] [--auth offline\|microsoft] [--account A] [--control HOST:PORT\|off]` | Runs the LAN bridge (below), until stopped. |
| `mccli bedrock login [--account A]` | Signs in with Microsoft for `--auth microsoft`: prints a device code to enter at microsoft.com/link, then waits. Once is enough; the tokens renew themselves. |
| `mccli bedrock auth-status`, `logout` | Whether the account is signed in (never asks); removes its tokens. |
| `mccli bedrock bridge-status` | The bridge: listening or not, its auth, a pending sign-in code, its clients. |
| `mccli bedrock state [--player P]` | What the bridge sees of a client's game: world, position, dimension, game mode, health, inventory, title, tab list, nearby players, open forms. |
| `mccli bedrock bridge-chat [--since N]`, `forms` | The chat and the forms the client received. |
| `mccli bedrock form-respond ID [JSON]` | Answers a form for the client (`0`, `true`, `["text", 3]`; nothing closes it). |

The client reads the keyboard once a frame, so mccli holds each key for a moment and moves the pointer before a click. With software rendering (llvmpipe, a few frames a second) a key can still be missed. After an action, check the result with a screenshot.

## Joining a server: the bridge

The Linux client (1.26) has two limits:

- It does not join a server by address without a Microsoft sign-in.
- Its network layer fails on direct connections: see minecraft-linux/mcpelauncher-manifest#2074.

It does join a **LAN world** over NetherNet. So `mccli bedrock bridge` does this:

- It advertises a LAN world on UDP 7551.
- It accepts the client over NetherNet (WebRTC).
- It relays the game to the server over RakNet, with [bedrock-protocol](https://github.com/PrismarineJS/bedrock-protocol)'s Relay.

The client's side is always offline. The server's side is chosen with `--auth`:

- `offline` (default): the server must allow offline players: `online-mode=false` on a dedicated server, or Geyser with `auth-type: offline` or Floodgate.
- `microsoft`: the bridge signs in with a Microsoft account and every client joins the server as that account, so online servers, Geyser with `auth-type: online` and Realms-style checks work. The account needs to own Bedrock for most servers.

```sh
cd bedrock && SKIP_BUILD=1 npm ci     # once (mccli does this itself); no cmake: postinstall links raknet-native's prebuilt binary
mccli bedrock bridge --target geyser.test.local:19132 --name "Test server"
mccli bedrock login                    # once, for --auth microsoft: enter the code at microsoft.com/link
mccli bedrock bridge --target play.example.net:19132 --auth microsoft
```

With `--auth microsoft` and no sign-in yet, the bridge itself prints the device code (`{"bridge": "sign-in", "code": ...}`, also in `bridge-status`) and starts listening once the account is signed in, so no client waits on it. Meanwhile it watches the token folder: a sign-in made there by `mccli bedrock login` or by minecraft-mcp-server's `bedrock-login` tool (an AI asks the person to enter the code) counts as well.

**Tokens.** They live in `MCCLI_BEDROCK_AUTH` (default `~/.cache/mccli-bedrock/auth`), owner-only files named `<account>_<cache>-cache.json`. minecraft-mcp-server's Bedrock bot writes the same layout: point both at one folder and one sign-in serves both. Keep the folder private and out of git. Behind an HTTPS proxy, Node needs `NODE_USE_ENV_PROXY=1` (Node 22.21+); mccli sets it when `HTTPS_PROXY` is set.

**The control API.** The bridge serves what it sees on `--control` (default `127.0.0.1:19150`): `GET /status`, `/state`, `/chat?since=N`, `/forms` (`?player=` picks a client; default the newest online one) and `POST /form {id, response}`. On an address other than loopback it needs `MCCLI_BEDROCK_BRIDGE_TOKEN` (16+ characters) and a `Bearer` header; the CLI sends it from the same variable and finds the bridge at `MCCLI_BEDROCK_BRIDGE_URL`.

Run the bridge **on another host or container than the client**, on the network of the client's default route:

- The client broadcasts its LAN discovery on that network only.
- A bridge on the client's own address gets the connection, but no game traffic.

On Docker, put the client and the bridge in the same user-defined bridge network, with inter-container traffic on.

Checked with Minecraft 1.26.60.4 (launcher 1.8.7) and a dedicated server 1.26.61. The client joined through the bridge, with no Microsoft sign-in, and the server saw it spawn.

The bridge's state was checked against a dedicated server 1.26.61 (`transport=raknet`, offline) with a bedrock-protocol client joining over NetherNet LAN: position, dimension, game mode, health, the tab list, chat both ways, `/give` items by name and a title. With `--auth microsoft`, `mccli bedrock login` got a device code from Microsoft; joining an online server with a signed-in account was not tested here.

A dedicated server from 1.26 on listens with `transport=nethernet` by default; the bridge speaks RakNet to the server, so set `transport=raknet` in its `server.properties`.
