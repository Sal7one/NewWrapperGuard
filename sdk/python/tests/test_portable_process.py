import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest

from wrapperguard_sdk.portable_process import bounded_process, ToolCancelledError


class PortableProcessTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="wg-portable-process-")
        self.root = Path(self.temporary.name).resolve()

    def tearDown(self):
        self.temporary.cleanup()

    def run_tool(self, script, *, maximum=8192, timeout=3.0, accepted=(0,), cancelled=None):
        return bounded_process([sys.executable, "-I", "-c", script], self.root, dict(os.environ),
                               time.monotonic() + timeout, maximum, accepted, cancelled=cancelled)

    def test_binary_stdout_exact_arguments_and_exit_codes(self):
        result = self.run_tool("import os,sys;os.write(1,b'first\\x00second');sys.exit(3)", accepted=(3,))
        self.assertEqual(b"first\x00second", result)

    def test_invalid_output_and_process_exit_do_not_leak_stderr_or_command(self):
        with self.assertRaisesRegex(ValueError, "^Tool failed$"):
            self.run_tool("import sys;sys.stderr.write('token=secret');sys.exit(9)")
        with self.assertRaisesRegex(ValueError, "^Tool output exceeds limit$"):
            self.run_tool("import os,time;os.write(1,b'x'*9000);time.sleep(30)", maximum=1024)

    def test_hanging_process_is_stopped_with_bounded_wait(self):
        started = time.monotonic()
        with self.assertRaises(TimeoutError):
            self.run_tool("import time;time.sleep(30)", timeout=0.15)
        self.assertLess(time.monotonic() - started, 2)

    def test_cancellation_before_launch_and_during_read(self):
        with self.assertRaises(ToolCancelledError):
            self.run_tool("from pathlib import Path;Path('marker').write_text('launched')", cancelled=lambda: True)
        self.assertFalse((self.root / "marker").exists())
        started = time.monotonic()
        with self.assertRaises(ToolCancelledError):
            self.run_tool("import time;time.sleep(30)", cancelled=lambda: time.monotonic() - started > 0.15)
        self.assertLess(time.monotonic() - started, 2)

    def test_no_output_and_large_bounded_output(self):
        self.assertEqual(b"", self.run_tool("pass"))
        self.assertEqual(b"x" * 150000, self.run_tool("import os;os.write(1,b'x'*150000)", maximum=150000))

    def test_completed_parent_with_inherited_pipe_cannot_hang_capture(self):
        # The enclosing host owns descendant group/job cleanup. This test checks
        # the pipe reader remains bounded even after its immediate child exits.
        pid = self.root / "descendant.pid"
        script = "import subprocess,sys;from pathlib import Path;p=subprocess.Popen([sys.executable,'-I','-c','import time;time.sleep(1)']);Path('descendant.pid').write_text(str(p.pid))"
        started = time.monotonic()
        try:
            with self.assertRaises(TimeoutError):
                self.run_tool(script, timeout=0.2)
            self.assertLess(time.monotonic() - started, 1)
        finally:
            if pid.exists():
                if os.name == "nt":
                    subprocess.run(["taskkill", "/PID", pid.read_text(), "/T", "/F"], stdout=subprocess.DEVNULL,
                                   stderr=subprocess.DEVNULL, timeout=3, check=False)
                else:
                    import signal
                    try:
                        os.kill(int(pid.read_text()), signal.SIGKILL)
                    except ProcessLookupError:
                        pass


if __name__ == "__main__":
    unittest.main()
