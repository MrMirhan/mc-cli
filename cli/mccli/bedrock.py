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
DATA = HOME / ".local/share/mcpelauncher"
WIDTH, HEIGHT = 1280, 720
# Where the menus are, in the window's own pixels, at the default 1280 x 720 size.
PLAY_BUTTON = (639, 372)
FIRST_LAN_WORLD = (178, 364)
POPUP_CLOSE = (977, 234)


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


def window() -> str | None:
    """The client's window id, when it shows one."""
    out = _run("xdotool", "search", "--onlyvisible", "--name", "^Minecraft$", check=False).split()
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
    return {"started": True, "running": True, "pid": proc.pid, "window": window(), "version": version, "copied": copied, "popups_closed": close_popups()}


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


def join_lan(index: int = 0, wait: float = 60.0) -> dict:
    """From the title screen: Play, then the LAN world at `index` (the bridge's), at the default size.
    Says what the screen shows after; check it with a screenshot."""
    win = _window_or_fail()
    _, _, w, h = geometry(win)
    if (w, h) != (WIDTH, HEIGHT):
        raise RuntimeError(f"join_lan knows the menus at {WIDTH} x {HEIGHT}; the window is {w} x {h}: click your way with click and screenshot")
    close_popups()
    click(*POPUP_CLOSE)
    time.sleep(1)
    click(*PLAY_BUTTON)
    time.sleep(5)
    click(FIRST_LAN_WORLD[0] + 300 * index, FIRST_LAN_WORLD[1])
    time.sleep(min(wait, 60.0))
    return {"joined": index, **screenshot()}


def quit_client() -> dict:
    pid = _pid()
    if pid:
        try:
            os.killpg(pid, signal.SIGTERM)
        except OSError:
            os.kill(pid, signal.SIGTERM)
    (_state() / "pid").unlink(missing_ok=True)
    return {"stopped": bool(pid)}


def logs(lines: int = 50) -> dict:
    path = _state() / "client.log"
    text = path.read_text(errors="replace").splitlines()[-lines:] if path.exists() else []
    keep = [line for line in text if "Image failed to load" not in line and not line.startswith("ALSA lib")]
    return {"lines": keep}


def bedrock_dir() -> Path:
    """The Node part (bridge.mjs, auth.mjs): next to the CLI in a checkout, or MCCLI_BEDROCK_NODE."""
    here = Path(__file__).resolve().parent.parent.parent / "bedrock"
    if (here / "bridge.mjs").exists():
        return here
    legacy = os.environ.get("MCCLI_BEDROCK_BRIDGE")  # the bridge script's path, from 1.8
    return Path(os.environ.get("MCCLI_BEDROCK_NODE") or (Path(legacy).parent if legacy else "/opt/mccli/bedrock"))


def node_env() -> dict:
    """Node's fetch ignores HTTPS_PROXY unless told; the Microsoft sign-in goes through it when one is set."""
    env = dict(os.environ)
    if (env.get("HTTPS_PROXY") or env.get("https_proxy")) and "NODE_USE_ENV_PROXY" not in env:
        env["NODE_USE_ENV_PROXY"] = "1"
    return env


def ensure_node_modules(folder: Path) -> None:
    """Installs the bridge's packages once. SKIP_BUILD keeps raknet-native from building with cmake; its
    postinstall links the prebuilt binary for this kernel instead."""
    if (folder / "node_modules" / "bedrock-protocol").exists():
        return
    env = dict(os.environ, SKIP_BUILD="1")
    subprocess.run(["npm", "ci", "--no-audit", "--no-fund"], cwd=str(folder), env=env, check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)


def bridge_args(target: str, name: str, auth: str = "offline", auth_dir: str | None = None, account: str = "default",
                control: str = "127.0.0.1:19150", version: str | None = None) -> list[str]:
    if auth not in ("offline", "microsoft"):
        raise ValueError("auth is offline or microsoft")
    args = ["--target", target, "--name", name, "--auth", auth, "--account", account, "--control", control]
    if auth_dir:
        args += ["--auth-dir", auth_dir]
    if version:
        args += ["--version", version]
    return args


def bridge(target: str, name: str, auth: str = "offline", auth_dir: str | None = None, account: str = "default",
           control: str = "127.0.0.1:19150", version: str | None = None) -> int:
    """Runs the LAN bridge (bedrock/bridge.mjs, Node) in the foreground until stopped. Its JSON lines say when
    it listens, when a client joins and, with --auth microsoft, the device code to sign in with."""
    folder = bedrock_dir()
    ensure_node_modules(folder)
    return subprocess.call(["node", str(folder / "bridge.mjs"), *bridge_args(target, name, auth, auth_dir, account, control, version)], env=node_env())


def auth(action: str, auth_dir: str | None = None, account: str = "default") -> int:
    """Microsoft sign-in for the bridge (and minecraft-mcp-server's Bedrock bot, when it shares the folder):
    login prints the device code, then the result, as JSON lines; status and logout one line."""
    folder = bedrock_dir()
    ensure_node_modules(folder)
    args = ["node", str(folder / "auth.mjs"), action, "--account", account]
    if auth_dir:
        args += ["--dir", auth_dir]
    return subprocess.call(args, env=node_env())


def _bridge_url(url: str | None) -> str:
    return (url or os.environ.get("MCCLI_BEDROCK_BRIDGE_URL") or "http://127.0.0.1:19150").rstrip("/")


def bridge_api(path: str, url: str | None = None, body: dict | None = None, timeout: float = 5.0) -> dict:
    """A call to the bridge's control API; the token comes from MCCLI_BEDROCK_BRIDGE_TOKEN."""
    import urllib.error
    import urllib.request
    req = urllib.request.Request(_bridge_url(url) + path, method="POST" if body is not None else "GET",
                                 data=json.dumps(body).encode() if body is not None else None,
                                 headers={"content-type": "application/json"})
    token = os.environ.get("MCCLI_BEDROCK_BRIDGE_TOKEN")
    if token:
        req.add_header("authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as res:
            return json.loads(res.read().decode())
    except urllib.error.HTTPError as e:
        try:
            detail = json.loads(e.read().decode()).get("error")
        except ValueError:
            detail = e.reason
        raise RuntimeError(f"The bridge answered {e.code}: {detail}") from None
    except (urllib.error.URLError, TimeoutError) as e:
        raise RuntimeError(f"No bridge at {_bridge_url(url)} ({getattr(e, 'reason', e)}): start it with `mccli bedrock bridge`") from None


def _query(**params) -> str:
    from urllib.parse import urlencode
    given = {k: v for k, v in params.items() if v is not None}
    return f"?{urlencode(given)}" if given else ""


def add_parser(sub) -> None:
    p = sub.add_parser("bedrock", help="Drive the real Bedrock client (mcpelauncher) on Linux")
    act = p.add_subparsers(dest="action", required=True)
    launch_p = act.add_parser("launch", help="Start the client from its game files")
    launch_p.add_argument("--version", help="Game version (default: the newest there are files for)")
    launch_p.add_argument("--width", type=int, default=WIDTH)
    launch_p.add_argument("--height", type=int, default=HEIGHT)
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
    act.add_parser("quit", help="Stop the client")
    logs_p = act.add_parser("logs", help="The client's log")
    logs_p.add_argument("--lines", type=int, default=50)
    bridge_p = act.add_parser("bridge", help="Show a server to clients on this network as a LAN world (Node)")
    bridge_p.add_argument("--target", required=True, help="The server: host:port (RakNet; Geyser or a dedicated server)")
    bridge_p.add_argument("--name", default="mccli test server", help="The LAN world's name")
    bridge_p.add_argument("--auth", choices=["offline", "microsoft"], default="offline",
                          help="The server side: offline (the server allows offline players) or signed in with Microsoft")
    bridge_p.add_argument("--auth-dir", help="Token folder (default MCCLI_BEDROCK_AUTH or ~/.cache/mccli-bedrock/auth)")
    bridge_p.add_argument("--account", default="default", help="Which signed-in account to use")
    bridge_p.add_argument("--control", default="127.0.0.1:19150", help="The control API's host:port, or off")
    bridge_p.add_argument("--version", help="Bedrock protocol version (default 1.26.60)")
    for name, text in (("login", "Sign in with Microsoft (device code) for --auth microsoft"),
                       ("auth-status", "Whether the account is signed in; never asks"),
                       ("logout", "Remove the account's tokens")):
        a = act.add_parser(name, help=text)
        a.add_argument("--auth-dir")
        a.add_argument("--account", default="default")
    for name, text in (("bridge-status", "The bridge: listening, auth, sign-in code when one is pending, its clients"),
                       ("state", "What the bridge sees of a client's game: position, health, inventory, forms, tab list"),
                       ("forms", "The forms the server opened for a client"),
                       ("bridge-chat", "Chat a client received, from the bridge")):
        a = act.add_parser(name, help=text)
        a.add_argument("--bridge", help="Control API URL (default MCCLI_BEDROCK_BRIDGE_URL or http://127.0.0.1:19150)")
        a.add_argument("--player", help="The client's player name (default: the newest online one)")
        if name == "bridge-chat":
            a.add_argument("--since", type=int, default=0)
            a.add_argument("--limit", type=int, default=50)
    form_p = act.add_parser("form-respond", help="Answer a form for the client (a button index, true/false or values)")
    form_p.add_argument("id", type=int)
    form_p.add_argument("response", nargs="?", help="JSON: 0, true, [\"text\", 3]; leave out to close the form")
    form_p.add_argument("--bridge")
    form_p.add_argument("--player")


def run(args) -> int:
    try:
        if args.action == "bridge":
            return bridge(args.target, args.name, args.auth, args.auth_dir, args.account, args.control, args.version)
        if args.action in ("login", "auth-status", "logout"):
            return auth({"auth-status": "status"}.get(args.action, args.action), args.auth_dir, args.account)
        if args.action == "form-respond":
            response = json.loads(args.response) if args.response is not None else None
            result = bridge_api("/form", args.bridge, {"player": args.player, "id": args.id, "response": response})
            print(json.dumps({"success": True, "data": result, "error": None}))
            return 0
        if args.action in ("bridge-status", "state", "forms", "bridge-chat"):
            path = {"bridge-status": "/status", "state": "/state", "forms": "/forms", "bridge-chat": "/chat"}[args.action]
            extra = {"since": args.since, "limit": args.limit} if args.action == "bridge-chat" else {}
            player = None if args.action == "bridge-status" else args.player
            print(json.dumps({"success": True, "data": bridge_api(path + _query(player=player, **extra), args.bridge), "error": None}))
            return 0
        result = {
            "launch": lambda: launch(args.version, args.width, args.height),
            "status": status,
            "screenshot": lambda: screenshot(args.path),
            "click": lambda: click(args.x, args.y, args.button, args.double),
            "key": lambda: key(args.keys),
            "type": lambda: type_text(args.text),
            "scroll": lambda: scroll(args.direction, args.amount),
            "chat": lambda: chat(args.message),
            "join-lan": lambda: join_lan(args.index),
            "quit": quit_client,
            "logs": lambda: logs(args.lines),
        }[args.action]()
        print(json.dumps({"success": True, "data": result, "error": None}))
        return 0
    except (RuntimeError, ValueError, OSError, subprocess.SubprocessError) as e:
        print(json.dumps({"success": False, "data": None, "error": {"message": str(e)}}))
        return 1
