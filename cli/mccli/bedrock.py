"""Bedrock Edition: drive the real Bedrock client on Linux (mcpelauncher), like the Java mod drives the Java client.

The Bedrock client cannot load a mod of ours, so this drives it from outside: it starts
`mcpelauncher-client` from licensed game files, finds its window, takes screenshots of it, and sends
it mouse and keys with xdotool. To join a server, run `mccli bedrock bridge` on another host (or
container) of the client's network: it shows the server to the client as a LAN world, which the
client joins without a Microsoft sign-in, and relays the game to the server (see docs/BEDROCK.md).

Nothing secret is in this code. The game files and the licence come from paths given at run time:
  MCCLI_BEDROCK_GAME         the launcher's data folder with versions/<version>/ and
                             mods/mcpelauncher-updates/<mod>/x86_64/ (default ~/.local/share/mcpelauncher)
  MCCLI_BEDROCK_CREDENTIALS  a folder laid out like a home folder, with the launcher's licence and
                             sign-in files; copied into the home folder when they are missing there
  MCCLI_BEDROCK_STATE        where the running client's pid, window and log are kept
                             (default ~/.cache/mccli-bedrock)
"""

import json
import os
import re
import shutil
import signal
import subprocess
import time
from pathlib import Path

HOME = Path.home()
# The launcher's licence file in the home folder (from MCCLI_BEDROCK_CREDENTIALS).
LICENCE = ".local/share/mcpelauncher/pass.token"
DATA = HOME / ".local/share/mcpelauncher"
WIDTH, HEIGHT = 1280, 720
# Where the menus are, in the window's own pixels, at the default 1280 x 720 size.
PLAY_BUTTON = (639, 372)
FIRST_LAN_WORLD = (178, 364)
GREEN, LIGHT = (60, 133, 39), (208, 209, 212)
PACK_GREEN, PACK_LIGHT = (33, 131, 6), (198, 198, 198)
# The dialogs the client opens on its own, at the default size: how to know one (pixels and their
# colours) and where to click: it closes the offers, and takes the server's resource packs.
DARK = (15, 15, 10)
# The "Connecting to multiplayer game" dialog and its Cancel button. The client's first connection to a
# bridge sometimes stays there; a second one goes in.
CONNECTING = [((600, 416), PACK_GREEN), ((680, 416), PACK_GREEN), ((400, 298), PACK_LIGHT), ((880, 298), PACK_LIGHT), ((400, 380), DARK), ((880, 380), DARK)]
CANCEL = (639, 416)
STUCK_AFTER = 12.0
DIALOGS = [
    ("welcome sign-in", [((785, 463), GREEN), ((1215, 463), GREEN), ((785, 519), LIGHT), ((1215, 519), LIGHT)], (999, 519)),
    ("marketplace offer", [((320, 493), GREEN), ((960, 493), GREEN)], (977, 234)),
    ("resource packs: download and join", [((360, 410), PACK_GREEN), ((920, 410), PACK_GREEN), ((360, 474), PACK_LIGHT), ((920, 474), PACK_LIGHT)], (639, 410)),
]


def _game() -> Path:
    return Path(os.environ.get("MCCLI_BEDROCK_GAME") or DATA)


def _state() -> Path:
    path = Path(os.environ.get("MCCLI_BEDROCK_STATE") or HOME / ".cache/mccli-bedrock")
    path.mkdir(parents=True, exist_ok=True)
    return path


def versions() -> list[str]:
    """The game versions there are files for, newest first."""
    root = _game() / "versions"
    found = [p.name for p in root.iterdir() if (p / "lib").is_dir()] if root.is_dir() else []
    return sorted(found, key=lambda v: [int(x) if x.isdigit() else 0 for x in v.split(".")], reverse=True)


def mod_dir() -> Path | None:
    """The licence mod (mcpelauncher-updates) that versions from 1.26 on need; its newest build."""
    root = _game() / "mods/mcpelauncher-updates"
    builds = sorted((p for p in root.glob("*/x86_64") if p.is_dir()), key=lambda p: p.parent.name) if root.is_dir() else []
    return builds[-1] if builds else None


def copy_credentials() -> list[str]:
    """Copies the licence and sign-in files from MCCLI_BEDROCK_CREDENTIALS into the home folder where
    they are missing; the paths it copied. A file already there stays: the client renews it."""
    source = os.environ.get("MCCLI_BEDROCK_CREDENTIALS")
    if not source:
        return []
    copied = []
    for src in Path(source).rglob("*"):
        if not src.is_file():
            continue
        rel = src.relative_to(source)
        dst = HOME / rel
        if dst.exists():
            continue
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(src, dst)
        dst.chmod(0o600)
        copied.append(str(rel))
    return copied


def _env() -> dict:
    env = dict(os.environ)
    env.setdefault("DISPLAY", ":1")
    return env


def _run(*args: str, check: bool = True) -> str:
    return subprocess.run(list(args), capture_output=True, text=True, timeout=30, env=_env(), check=check).stdout


def _pid() -> int | None:
    try:
        pid = int((_state() / "pid").read_text())
        os.kill(pid, 0)
        return pid
    except (OSError, ValueError):
        return None


def clients() -> list[int]:
    """The pids of the Bedrock clients running here, ours or left from before."""
    found = []
    for proc in Path("/proc").glob("[0-9]*"):
        try:
            argv0 = (proc / "cmdline").read_bytes().split(b"\0")[0]
        except OSError:
            continue
        if argv0.endswith(b"mcpelauncher-client") and int(proc.name) != os.getpid():
            found.append(int(proc.name))
    return found


def window() -> str | None:
    """The client's window id, when it shows one: the window of the client mccli started."""
    pid = _pid()
    by_pid = ["--pid", str(pid)] if pid else []
    out = _run("xdotool", "search", "--onlyvisible", *by_pid, "--name", "^Minecraft$", check=False).split()
    return out[0] if out else None


def geometry(win: str) -> tuple[int, int, int, int]:
    """The window's place on the screen and its size: x, y, width, height."""
    info = _run("xwininfo", "-id", win)
    value = lambda name: int(re.search(rf"{name}:\s+(-?\d+)", info).group(1))
    return value("Absolute upper-left X"), value("Absolute upper-left Y"), value("Width"), value("Height")


def close_popups() -> int:
    """Closes the launcher's error popups (a missing sound device, for one); how many."""
    closed = 0
    for proc in Path("/proc").glob("[0-9]*"):
        try:
            cmdline = (proc / "cmdline").read_bytes().split(b"\0")
        except OSError:
            continue
        if cmdline and cmdline[0].endswith(b"mcpelauncher-error") and int(proc.name) != os.getpid():
            try:
                os.kill(int(proc.name), signal.SIGTERM)
                closed += 1
            except OSError:
                pass
    return closed


def launch(version: str | None = None, width: int = WIDTH, height: int = HEIGHT, wait: float = 90.0) -> dict:
    """Starts the client, unless it runs; waits for its window."""
    if _pid() and window():
        return {"started": False, "running": True, "pid": _pid(), "window": window()}
    if clients():
        quit_client()  # a client left from before (its window is not ours to drive)
    found = versions()
    version = version or (found[0] if found else None)
    if not version or version not in found:
        raise RuntimeError(f"No game files for {version or 'any version'} in {_game()}/versions (have: {', '.join(found) or 'none'})")
    copied = copy_credentials()
    args = ["mcpelauncher-client", "-dg", str(_game() / "versions" / version), "-ww", str(width), "-wh", str(height)]
    mods = mod_dir()
    if mods:
        args += ["-m", str(mods)]
    log = open(_state() / "client.log", "ab")
    proc = subprocess.Popen(args, stdout=log, stderr=subprocess.STDOUT, env=_env(), start_new_session=True, cwd=str(HOME))
    (_state() / "pid").write_text(str(proc.pid))
    deadline = time.time() + wait
    while time.time() < deadline and proc.poll() is None:
        if window():
            break
        time.sleep(1)
    if proc.poll() is not None:
        raise RuntimeError(f"The client stopped (exit {proc.returncode}); see {_state() / 'client.log'}")
    time.sleep(3)
    popups = close_popups()
    # The window can go for a moment as the client settles (after its error popup closes, for one).
    win = window()
    for _ in range(15):
        if win or proc.poll() is not None:
            break
        time.sleep(1)
        win = window()
    if not win:
        # Without its licence the client runs on, but never opens the game's window.
        quit_client()
        why = (f"it has no licence: set MCCLI_BEDROCK_CREDENTIALS to the folder with the launcher's licence files (docs/BEDROCK.md)"
               if not (HOME / LICENCE).exists() else f"see {_state() / 'client.log'}")
        raise RuntimeError(f"The client shows no window: {why}")
    return {"started": True, "running": True, "pid": proc.pid, "window": win, "version": version, "copied": copied, "popups_closed": popups}


def status() -> dict:
    pid, win = _pid(), window()
    out = {"running": bool(pid), "pid": pid, "window": win, "versions": versions(), "mod": str(mod_dir()) if mod_dir() else None}
    if win:
        x, y, w, h = geometry(win)
        out["geometry"] = {"x": x, "y": y, "width": w, "height": h}
    return out


def _window_or_fail() -> str:
    win = window()
    if not win:
        raise RuntimeError("The Bedrock client shows no window: start it with `mccli bedrock launch`")
    return win


def screenshot(path: str | None = None) -> dict:
    """A PNG of the client's window, as the player sees it."""
    win = _window_or_fail()
    target = Path(path or _state() / "screenshot.png")
    _run("import", "-window", win, str(target))
    size = _run("identify", "-format", "%w %h", str(target)).split()
    return {"path": str(target.absolute()), "width": int(size[0]), "height": int(size[1])}


def click(x: int, y: int, button: str = "left", double: bool = False) -> dict:
    """Clicks at x, y of the window (the screenshot's pixels). The client takes a click only after the
    pointer moved and rested on it, so the pointer comes from a little away and waits."""
    win = _window_or_fail()
    wx, wy, w, h = geometry(win)
    if not (0 <= x < w and 0 <= y < h):
        raise ValueError(f"{x}, {y} is outside the window ({w} x {h})")
    number = {"left": "1", "middle": "2", "right": "3"}[button]
    _run("xdotool", "windowactivate", "--sync", win, check=False)
    ax, ay = wx + x, wy + y
    presses = ["mousedown", number, "sleep", "0.15", "mouseup", number] * (2 if double else 1)
    _run("xdotool", "mousemove", str(ax - 6), str(ay - 4), "sleep", "0.3", "mousemove", str(ax), str(ay), "sleep", "0.3", *presses)
    return {"clicked": [x, y], "button": button, "double": double}


def key(keys: list[str]) -> dict:
    """Presses each key, held for a moment: the client reads the keys once a frame, and a quick press
    falls between two frames when it draws slowly (software rendering)."""
    win = _window_or_fail()
    _run("xdotool", "windowactivate", "--sync", win, check=False)
    for k in keys:
        _run("xdotool", "keydown", "--clearmodifiers", k, "sleep", "0.2", "keyup", k)
        time.sleep(0.2)
    return {"keys": keys}


def type_text(text: str) -> dict:
    win = _window_or_fail()
    _run("xdotool", "windowactivate", "--sync", win, check=False)
    _run("xdotool", "type", "--delay", "60", text)
    return {"typed": len(text)}


def scroll(direction: str, amount: int = 3) -> dict:
    win = _window_or_fail()
    _run("xdotool", "windowactivate", "--sync", win, check=False)
    button = {"up": "4", "down": "5"}[direction]
    _run("xdotool", "click", "--repeat", str(amount), "--delay", "80", button)
    return {"scrolled": direction, "amount": amount}


def chat(message: str) -> dict:
    """Says `message` in chat, or runs it when it starts with a slash, from the game view (no screen
    open). T opens the chat; a click puts the text box in focus; Return sends; Escape closes the chat,
    which stays open. A slow client (software rendering) can miss a key: check with a screenshot."""
    win = _window_or_fail()
    _, _, w, h = geometry(win)
    key(["t"])
    time.sleep(2.0)
    click(w // 2, h - 36)
    time.sleep(0.5)
    type_text(message)
    time.sleep(0.5)
    key(["Return"])
    time.sleep(1.0)
    key(["Escape"])
    return {"sent": message}


def pixel(png: str, x: int, y: int) -> tuple[int, int, int]:
    """The colour of one pixel of a PNG (ImageMagick)."""
    out = _run("convert", png, "-crop", f"1x1+{x}+{y}", "txt:-")
    m = re.search(r"\((\d+),(\d+),(\d+)", out.splitlines()[-1] if out else "")
    return (int(m.group(1)), int(m.group(2)), int(m.group(3))) if m else (-1, -1, -1)


def _matches(png: str, marks) -> bool:
    """Whether the screenshot has each mark's colour at its place (within 30 of each channel)."""
    return all(all(abs(a - b) <= 30 for a, b in zip(pixel(png, x, y), c)) for (x, y), c in marks)


def dismiss(rounds: int = 3) -> list[str]:
    """Closes the dialogs the client opens on its own (the sign-in offer, the Marketplace offer), as it
    knows them at the default size; the ones it closed."""
    closed = []
    close_popups()
    for _ in range(rounds):
        shot = screenshot(str(_state() / "dismiss.png"))["path"]
        if (geometry(_window_or_fail())[2:]) != (WIDTH, HEIGHT):
            return closed
        found = next((d for d in DIALOGS if _matches(shot, d[1])), None)
        if not found:
            return closed
        click(*found[2])
        closed.append(found[0])
        time.sleep(2)
    return closed


def join_lan(index: int = 0, wait: float = 45.0) -> dict:
    """From the title screen: Play, then the LAN world at `index` (the bridge's), at the default size.
    Closes the dialogs it knows on the way. Says what the screen shows after; check it with a screenshot."""
    win = _window_or_fail()
    _, _, w, h = geometry(win)
    if (w, h) != (WIDTH, HEIGHT):
        raise RuntimeError(f"join_lan knows the menus at {WIDTH} x {HEIGHT}; the window is {w} x {h}: click your way with click and screenshot")
    closed = dismiss()
    click(*PLAY_BUTTON)
    time.sleep(5)
    closed += dismiss()
    world = (FIRST_LAN_WORLD[0] + 300 * index, FIRST_LAN_WORLD[1])
    click(*world)
    deadline = time.time() + min(wait, 60.0)
    time.sleep(8)
    closed += dismiss()  # the server's resource packs
    retried, stuck = 0, None
    while time.time() < deadline:
        if _matches(screenshot(str(_state() / "join.png"))["path"], CONNECTING):
            stuck = stuck or time.time()
            if time.time() - stuck >= STUCK_AFTER and retried < 2:
                # Cancel goes back to the worlds, where the bridge's world still is: join it again.
                click(*CANCEL)
                time.sleep(4)
                click(*world)
                retried, stuck = retried + 1, None
                time.sleep(6)
                closed += dismiss()
                continue
        else:
            stuck = None
            closed += dismiss()
        time.sleep(3)
    return {"joined": index, "closed": closed, "retried": retried, **screenshot()}


def quit_client() -> dict:
    """Stops every Bedrock client here: asked first, then made to (the client may not stop when asked)."""
    pids = clients()
    for sig in (signal.SIGTERM, signal.SIGKILL):
        for pid in clients():
            try:
                os.kill(pid, sig)
            except OSError:
                pass
        deadline = time.time() + 5
        while clients() and time.time() < deadline:
            time.sleep(0.5)
        if not clients():
            break
    (_state() / "pid").unlink(missing_ok=True)
    close_popups()
    return {"stopped": len(pids)}


def logs(lines: int = 50) -> dict:
    path = _state() / "client.log"
    text = path.read_text(errors="replace").splitlines()[-lines:] if path.exists() else []
    keep = [line for line in text if "Image failed to load" not in line and not line.startswith("ALSA lib")]
    return {"lines": keep}


def _bridge_script() -> Path:
    script = Path(__file__).resolve().parent.parent.parent / "bedrock" / "bridge.mjs"
    return script if script.exists() else Path(os.environ.get("MCCLI_BEDROCK_BRIDGE", "/opt/mccli/bedrock/bridge.mjs"))


def bridge(target: str, name: str, offline: bool = False) -> int:
    """Runs the LAN bridge (bedrock/bridge.mjs, Node) in the foreground until stopped. It joins the
    server as the Xbox account signed in with `login` (MCCLI_BEDROCK_AUTH), unless `offline`."""
    return subprocess.call(["node", str(_bridge_script()), "--target", target, "--name", name, *(["--offline"] if offline else [])])


def login() -> int:
    """Signs the bridge in to Xbox once, into MCCLI_BEDROCK_AUTH: it prints a code to enter at
    microsoft.com/link, and waits for the sign-in."""
    if not os.environ.get("MCCLI_BEDROCK_AUTH"):
        print(json.dumps({"success": False, "data": None, "error": {"message": "Set MCCLI_BEDROCK_AUTH to the folder that keeps the sign-in"}}))
        return 1
    return subprocess.call(["node", str(_bridge_script()), "--login"])


def add_parser(sub) -> None:
    p = sub.add_parser("bedrock", help="Drive the real Bedrock client (mcpelauncher) on Linux")
    act = p.add_subparsers(dest="action", required=True)
    launch_p = act.add_parser("launch", help="Start the client from its game files")
    launch_p.add_argument("--version", help="Game version (default: the newest there are files for)")
    launch_p.add_argument("--width", type=int, default=WIDTH)
    launch_p.add_argument("--height", type=int, default=HEIGHT)
    launch_p.add_argument("--wait", type=float, default=90.0, help="Seconds to wait for its window")
    act.add_parser("status", help="Whether it runs, its window, the versions there are")
    shot_p = act.add_parser("screenshot", help="A PNG of the client's window")
    shot_p.add_argument("--path")
    click_p = act.add_parser("click", help="Click at x y of the window")
    click_p.add_argument("x", type=int)
    click_p.add_argument("y", type=int)
    click_p.add_argument("--button", choices=["left", "middle", "right"], default="left")
    click_p.add_argument("--double", action="store_true")
    key_p = act.add_parser("key", help="Press keys (xdotool names: Escape, Return, w, ctrl+a)")
    key_p.add_argument("keys", nargs="+")
    type_p = act.add_parser("type", help="Type text")
    type_p.add_argument("text")
    scroll_p = act.add_parser("scroll", help="Turn the mouse wheel")
    scroll_p.add_argument("direction", choices=["up", "down"])
    scroll_p.add_argument("--amount", type=int, default=3)
    chat_p = act.add_parser("chat", help="Say something in chat, or run a /command")
    chat_p.add_argument("message")
    lan_p = act.add_parser("join-lan", help="From the title screen, join the LAN world (the bridge's)")
    lan_p.add_argument("--index", type=int, default=0)
    lan_p.add_argument("--wait", type=float, default=45.0, help="Seconds to wait for the world to load (at most 60)")
    act.add_parser("quit", help="Stop the client")
    logs_p = act.add_parser("logs", help="The client's log")
    logs_p.add_argument("--lines", type=int, default=50)
    bridge_p = act.add_parser("bridge", help="Show a server to clients on this network as a LAN world (Node)")
    bridge_p.add_argument("--target", required=True, help="The server: host:port (RakNet: any Bedrock server, Geyser, or a dedicated server)")
    bridge_p.add_argument("--name", default="mccli test server", help="The LAN world's name")
    bridge_p.add_argument("--offline", action="store_true", help="Join as an offline player, not as the signed-in Xbox account")
    act.add_parser("login", help="Sign the bridge in to Xbox once (MCCLI_BEDROCK_AUTH), for servers that ask for an account")


def run(args) -> int:
    try:
        if args.action == "bridge":
            return bridge(args.target, args.name, args.offline)
        if args.action == "login":
            return login()
        result = {
            "launch": lambda: launch(args.version, args.width, args.height, args.wait),
            "status": status,
            "screenshot": lambda: screenshot(args.path),
            "click": lambda: click(args.x, args.y, args.button, args.double),
            "key": lambda: key(args.keys),
            "type": lambda: type_text(args.text),
            "scroll": lambda: scroll(args.direction, args.amount),
            "chat": lambda: chat(args.message),
            "join-lan": lambda: join_lan(args.index, args.wait),
            "quit": quit_client,
            "logs": lambda: logs(args.lines),
        }[args.action]()
        print(json.dumps({"success": True, "data": result, "error": None}))
        return 0
    except (RuntimeError, ValueError, OSError, subprocess.SubprocessError) as e:
        print(json.dumps({"success": False, "data": None, "error": {"message": str(e)}}))
        return 1
