import ctypes
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest

from wrapperguard_sdk.portable_process import bounded_process
from wrapperguard_sdk.windows_job import WindowsJobProcess, _environment_block


class EnvironmentTests(unittest.TestCase):
    def test_environment_is_bounded_unambiguous_and_cannot_add_block_entries(self):
        self.assertEqual('A=one\x00B=two\x00\x00', _environment_block({'B': 'two', 'A': 'one'}))
        for value in ({'A': 'x\x00Injected=secret'}, {'A=B': 'x'}, {'Path': 'a', 'PATH': 'b'}, {'A': 'x' * 33000}):
            with self.assertRaises(ValueError): _environment_block(value)

    @unittest.skipIf(os.name == 'nt', 'non-Windows guard')
    def test_job_never_falls_back_to_uncontained_launch(self):
        with self.assertRaises(NotImplementedError): WindowsJobProcess([sys.executable], Path.cwd(), {'A': 'one'})


@unittest.skipUnless(os.name == 'nt', 'requires actual Windows Job Objects')
class JobTreeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='wg-job-tree-'); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()

    def assert_dead(self, pid):
        kernel = ctypes.WinDLL('kernel32', use_last_error=True)
        kernel.OpenProcess.argtypes = [ctypes.c_uint32, ctypes.c_int32, ctypes.c_uint32]
        kernel.OpenProcess.restype = ctypes.c_void_p
        kernel.WaitForSingleObject.argtypes = [ctypes.c_void_p, ctypes.c_uint32]
        kernel.WaitForSingleObject.restype = ctypes.c_uint32
        kernel.CloseHandle.argtypes = [ctypes.c_void_p]
        handle = kernel.OpenProcess(0x100000, 0, pid)
        if handle:
            try: self.assertEqual(0, kernel.WaitForSingleObject(handle, 2000), 'Descendant must have terminated')
            finally: kernel.CloseHandle(handle)

    def test_deadline_kills_grandchild_even_after_parent_exits(self):
        code = "import subprocess,sys;from pathlib import Path;p=subprocess.Popen([sys.executable,'-I','-c','import time;time.sleep(30)']);Path('grandchild.pid').write_text(str(p.pid))"
        with self.assertRaises(TimeoutError):
            bounded_process([sys.executable, '-I', '-c', code], self.root, dict(os.environ), time.monotonic()+0.5, 8192)
        self.assert_dead(int((self.root / 'grandchild.pid').read_text()))

    def test_early_success_also_closes_job_and_terminates_background_descendant(self):
        code = "import subprocess,sys;from pathlib import Path;p=subprocess.Popen([sys.executable,'-I','-c','import time;time.sleep(30)'],stdout=subprocess.DEVNULL);Path('grandchild.pid').write_text(str(p.pid));print('done')"
        self.assertEqual(b'done\r\n', bounded_process([sys.executable, '-I', '-c', code], self.root,
            dict(os.environ), time.monotonic()+3, 8192))
        self.assert_dead(int((self.root / 'grandchild.pid').read_text()))

    def test_abrupt_sdk_termination_closes_last_job_handle(self):
        package_root = str(Path(__file__).resolve().parents[1] / 'src')
        inner = "import subprocess,sys,time;from pathlib import Path;p=subprocess.Popen([sys.executable,'-I','-c','import time;time.sleep(30)']);Path('grandchild.pid').write_text(str(p.pid));time.sleep(30)"
        outer = ('import sys,os,time;sys.path.insert(0,' + repr(package_root) + ');'
                 'from pathlib import Path;from wrapperguard_sdk.portable_process import bounded_process;'
                 'bounded_process([sys.executable,"-I","-c",' + repr(inner) + '],Path.cwd(),dict(os.environ),time.monotonic()+30,8192)')
        host = subprocess.Popen([sys.executable, '-I', '-c', outer], cwd=self.root,
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            deadline = time.monotonic()+5
            while not (self.root / 'grandchild.pid').exists() and time.monotonic() < deadline: time.sleep(.02)
            self.assertTrue((self.root / 'grandchild.pid').exists(), 'Nested child must run before SDK cancellation')
            pid = int((self.root / 'grandchild.pid').read_text()); host.kill(); host.wait(timeout=2)
            self.assert_dead(pid)
        finally:
            if host.poll() is None: host.kill(); host.wait(timeout=2)


if __name__ == '__main__': unittest.main()
