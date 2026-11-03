import io
import json
import os
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

from mccli import bedrock


class BedrockTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.root = Path(self.dir.name)
        self.env = mock.patch.dict(os.environ, {"MCCLI_BEDROCK_GAME": str(self.root / "game"), "MCCLI_BEDROCK_STATE": str(self.root / "state")})
        self.env.start()

    def tearDown(self):
        self.env.stop()
        self.dir.cleanup()

    def test_versions_are_the_folders_with_game_files_newest_first(self):
        for v in ["1.26.45.1", "1.26.60.4", "1.26.51.2"]:
            (self.root / "game/versions" / v / "lib").mkdir(parents=True)
        (self.root / "game/versions/1.26.70.0").mkdir(parents=True)  # no game files yet
        self.assertEqual(bedrock.versions(), ["1.26.60.4", "1.26.51.2", "1.26.45.1"])

    def test_the_newest_licence_mod_is_taken(self):
        self.assertIsNone(bedrock.mod_dir())
        for m in ["1.26.30.1", "1.26.45.1"]:
            (self.root / "game/mods/mcpelauncher-updates" / m / "x86_64").mkdir(parents=True)
        self.assertEqual(bedrock.mod_dir().parent.name, "1.26.45.1")

    def test_credentials_are_copied_into_home_only_where_missing(self):
        source = self.root / "secrets"
        (source / ".local/share/mcpelauncher/xal").mkdir(parents=True)
        (source / ".local/share/mcpelauncher/pass.token").write_text("new")
        (source / ".local/share/mcpelauncher/xal/token").write_text("xbox")
        home = self.root / "home"
        (home / ".local/share/mcpelauncher").mkdir(parents=True)
        (home / ".local/share/mcpelauncher/pass.token").write_text("renewed by the client")
        with mock.patch.object(bedrock, "HOME", home), mock.patch.dict(os.environ, {"MCCLI_BEDROCK_CREDENTIALS": str(source)}):
            copied = bedrock.copy_credentials()
        self.assertEqual(copied, [".local/share/mcpelauncher/xal/token"])
        self.assertEqual((home / ".local/share/mcpelauncher/pass.token").read_text(), "renewed by the client")
        self.assertEqual(oct((home / ".local/share/mcpelauncher/xal/token").stat().st_mode & 0o777), "0o600")

    def test_launch_refuses_a_version_without_game_files(self):
        with self.assertRaisesRegex(RuntimeError, "No game files for 1.26.99"):
            with mock.patch.object(bedrock, "_pid", return_value=None):
                bedrock.launch("1.26.99")

    def test_launch_without_a_window_stops_the_client_and_names_the_missing_licence(self):
        (self.root / "game/versions/1.26.60.4/lib").mkdir(parents=True)
        home = self.root / "home"
        proc = mock.Mock(pid=7, poll=mock.Mock(return_value=None))
        with mock.patch.object(bedrock, "HOME", home), mock.patch.object(bedrock, "_pid", return_value=None), \
             mock.patch.object(bedrock, "clients", return_value=[]), mock.patch.object(bedrock, "window", return_value=None), \
             mock.patch.object(bedrock, "close_popups", return_value=0), mock.patch.object(bedrock, "quit_client") as quit_client, \
             mock.patch.object(bedrock.subprocess, "Popen", return_value=proc), mock.patch.object(bedrock.time, "sleep"):
            with self.assertRaisesRegex(RuntimeError, "shows no window: it has no licence: set MCCLI_BEDROCK_CREDENTIALS"):
                bedrock.launch(wait=0)
            quit_client.assert_called_once()
            (home / bedrock.LICENCE).parent.mkdir(parents=True)
            (home / bedrock.LICENCE).write_text("licence")
            with self.assertRaisesRegex(RuntimeError, "shows no window: see .*client.log"):
                bedrock.launch(wait=0)

    def test_a_click_outside_the_window_is_refused_and_one_inside_comes_slowly_at_its_screen_place(self):
        calls = []
        with mock.patch.object(bedrock, "window", return_value="42"), \
             mock.patch.object(bedrock, "geometry", return_value=(5, 56, 1280, 720)), \
             mock.patch.object(bedrock, "_run", side_effect=lambda *a, **k: calls.append(a) or ""):
            with self.assertRaisesRegex(ValueError, "outside the window"):
                bedrock.click(1300, 10)
            bedrock.click(639, 372)
        move = calls[-1]
        self.assertEqual(move[:3], ("xdotool", "mousemove", "638"))
        self.assertIn("644", move)
        self.assertIn("428", move)
        self.assertEqual(move.count("mousedown"), 1)

    def test_run_answers_json_and_says_why_on_failure(self):
        out = io.StringIO()
        with mock.patch.object(bedrock, "window", return_value=None), redirect_stdout(out):
            code = bedrock.run(SimpleNamespace(action="screenshot", path=None))
        self.assertEqual(code, 1)
        self.assertIn("start it with `mccli bedrock launch`", json.loads(out.getvalue())["error"]["message"])


if __name__ == "__main__":
    unittest.main()
