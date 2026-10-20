import contextlib
import importlib.util
import io
import json
import os
import signal
from pathlib import Path
import sys
import tempfile
import time
import unittest
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location('benchmarks', Path(__file__).parents[1]/'benchmark-scans.py')
bench = importlib.util.module_from_spec(spec); spec.loader.exec_module(bench)


class MetricTests(unittest.TestCase):
    @unittest.skipUnless(os.name == 'posix', 'POSIX process-group signals')
    def test_exited_group_eperm_requires_actual_esrch_before_cleanup_succeeds(self):
        process = Mock(pid=12345); process.poll.return_value = -signal.SIGTERM
        denied = PermissionError('group may still contain live members')
        with patch.object(bench.os, 'killpg', side_effect=[None, denied, PermissionError(), ProcessLookupError()]) as kill:
            bench._stop(process)
        self.assertEqual([(12345,signal.SIGTERM),(12345,signal.SIGKILL),(12345,0),(12345,0)],
                         [call.args for call in kill.call_args_list])

    @unittest.skipUnless(os.name == 'posix', 'POSIX process-group signals')
    def test_permission_denied_live_or_unverifiable_groups_are_never_reported_cleaned(self):
        for probe in (None, PermissionError('still unobservable')):
            with self.subTest(probe=probe):
                process = Mock(pid=12345); process.poll.return_value = -signal.SIGTERM
                denied = PermissionError('cannot kill live descendants')
                with patch.object(bench.os, 'killpg', side_effect=[None, denied, probe]), \
                        patch.object(bench.time, 'monotonic', side_effect=[0,.1,.6]), \
                        patch.object(bench.time, 'sleep'), self.assertRaises(PermissionError) as error:
                    bench._stop(process)
                self.assertIs(denied, error.exception)
        process = Mock(pid=12345); process.poll.return_value = None
        with patch.object(bench.os, 'killpg', side_effect=PermissionError()) as kill, self.assertRaises(PermissionError):
            bench._stop(process)
        self.assertEqual(1, kill.call_count)

    def test_actual_time_formats_normalize_linux_kib_and_macos_bytes(self):
        self.assertEqual(1048576, bench._metrics('WRAPPERGUARD_RSS_KIB=1024\n', 'Linux'))
        self.assertEqual(1048576, bench._metrics(' 1048576  maximum resident set size\n', 'Darwin'))
        for raw in ('', 'WRAPPERGUARD_RSS_KIB=0', 'WRAPPERGUARD_RSS_KIB=1\nWRAPPERGUARD_RSS_KIB=2', 'private arbitrary stderr'):
            self.assertIsNone(bench._metrics(raw, 'Linux'))

    def test_partial_stale_invalid_and_cancelled_scans_cannot_become_clean(self):
        report = {'schemaVersion': 1, 'complete': False, 'stale': False, 'verdict': 'INCOMPLETE', 'scannedFiles': 2,
            'findings': [{'message': 'PRIVATE SOURCE'}], 'notices': [{'code': 'WG-FILE-SKIPPED', 'message': 'PRIVATE EVIDENCE'}]}
        result = bench.scan_summary(json.dumps(report).encode(), 1, 'FINISHED')
        self.assertFalse(result['complete']); self.assertEqual('VALID_REPORT', result['reportStatus'])
        self.assertEqual(['WG-FILE-SKIPPED'], result['noticeCodes']); self.assertNotIn('PRIVATE', json.dumps(result))
        report['complete'] = True
        self.assertFalse(bench.scan_summary(json.dumps(report).encode(), 1, 'FINISHED')['complete'])
        report['stale'] = True
        self.assertFalse(bench.scan_summary(json.dumps(report).encode(), 0, 'FINISHED')['complete'])
        report['stale'] = 'false'
        self.assertEqual('INVALID_REPORT', bench.scan_summary(json.dumps(report).encode(), 0, 'FINISHED')['reportStatus'])
        for status in ('CANCELLED', 'OUTPUT_LIMIT'):
            self.assertFalse(bench.scan_summary(b'{"complete":true}', 0, status)['complete'])

    def test_aggregate_keeps_incomplete_counts_and_excludes_warmups_and_cancellations(self):
        runs = [{'warmup': True, 'elapsedMs': 9999, 'peakRssBytes': 9999, 'processStatus': 'FINISHED', 'complete': True},
                {'warmup': False, 'elapsedMs': 10, 'peakRssBytes': 100, 'processStatus': 'FINISHED', 'complete': True},
                {'warmup': False, 'elapsedMs': 30, 'peakRssBytes': 200, 'processStatus': 'FINISHED', 'complete': False},
                {'warmup': False, 'elapsedMs': 500, 'peakRssBytes': None, 'processStatus': 'CANCELLED', 'complete': False}]
        result = bench.aggregate(runs)
        self.assertEqual(20, result['p50ElapsedMs']); self.assertEqual(30, result['p95ElapsedMs'])
        self.assertEqual(1, result['incompleteRuns']); self.assertEqual(1, result['cancelledRuns']); self.assertEqual(200, result['maxPeakRssBytes'])


@unittest.skipUnless(os.name == 'posix', 'POSIX resource measurement/process groups')
class RealMeasurementTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.user = Path(self.temp.name).resolve()

    def test_real_process_rss_and_stdout_are_measured_without_stderr_content(self):
        result, raw = bench.measured_command([sys.executable, '-I', '-c', "import sys;print('result');sys.stderr.write('PRIVATE STDERR')"], 3)
        self.assertEqual(b'result\n', raw); self.assertTrue(result['measurementComplete'])
        self.assertGreater(result['peakRssBytes'], 1024*1024); self.assertNotIn('PRIVATE', json.dumps(result))

    def test_output_budget_and_deadline_stop_the_actual_process_group(self):
        result, raw = bench.measured_command([sys.executable, '-I', '-c', "import os,time;os.write(1,b'x'*10000);time.sleep(10)"], 3, maximum=64)
        self.assertEqual('OUTPUT_LIMIT', result['processStatus']); self.assertEqual(64, len(raw)); self.assertFalse(result['measurementComplete'])
        pid_file = self.user/'child.pid'
        program = 'import os,time;from pathlib import Path;Path('+repr(str(pid_file))+').write_text(str(os.getpid()));time.sleep(10)'
        result, raw = bench.measured_command([sys.executable, '-I', '-c', program], .3)
        self.assertEqual('CANCELLED', result['processStatus']); self.assertFalse(result['measurementComplete'])
        pid = int(pid_file.read_text()); stop = time.monotonic()+2
        while True:
            try: os.kill(pid, 0)
            except ProcessLookupError: break
            self.assertLess(time.monotonic(), stop, 'Timed-out benchmark process must terminate')
            time.sleep(.01)

    def test_deadline_kills_actual_term_resistant_descendants_after_the_timed_leader_exits(self):
        parent_pid = self.user/'parent.pid'; child_pid = self.user/'descendant.pid'
        child = ('import os,signal,time;from pathlib import Path;signal.signal(signal.SIGTERM,signal.SIG_IGN);'
                 'Path('+repr(str(child_pid))+').write_text(str(os.getpid()));time.sleep(10)')
        parent = ('import os,signal,subprocess,time;from pathlib import Path;'
                  'signal.signal(signal.SIGTERM,signal.SIG_IGN);'
                  'subprocess.Popen(['+repr(sys.executable)+',"-I","-c",'+repr(child)+']);'
                  'Path('+repr(str(parent_pid))+').write_text(str(os.getpid()));time.sleep(10)')
        result, _ = bench.measured_command([sys.executable,'-I','-c',parent], .8)
        self.assertEqual('CANCELLED', result['processStatus']); self.assertFalse(result['measurementComplete'])
        self.assertLess(result['elapsedMs'], 3000)
        for file in (parent_pid, child_pid):
            pid = int(file.read_text()); deadline = time.monotonic()+2
            while True:
                try: os.kill(pid,0)
                except ProcessLookupError: break
                self.assertLess(time.monotonic(), deadline, 'TERM-resistant descendant must actually terminate')
                time.sleep(.01)

    def fake_cli(self):
        cli = self.user/'reviewed-cli'
        cli.write_text('#!'+sys.executable+'\n'+'''import json,sys
if '--version' in sys.argv:
 print('WrapperGuard 0.7.0');raise SystemExit(0)
partial = 'partial' in sys.argv[-1]
print(json.dumps({'schemaVersion':1,'complete':not partial,'stale':False,'verdict':'INCOMPLETE' if partial else 'CLEAN',
 'scannedFiles':2,'findings':[{'message':'PRIVATE PROJECT SECRET'}],'notices':[]}))
raise SystemExit(1 if partial else 0)
''')
        cli.chmod(0o700); return cli

    def test_report_records_real_resources_preserves_partial_coverage_and_never_imports_source(self):
        cli = self.fake_cli(); complete = self.user/'complete'; complete.mkdir(); partial = self.user/'partial'; partial.mkdir()
        marker = self.user/'source-executed'
        (complete/'setup.py').write_text('from pathlib import Path\nPath('+repr(str(marker))+').touch()')
        output = self.user/'bench.json'
        with contextlib.redirect_stdout(io.StringIO()):
            code = bench.main(['--cli',str(cli),'--project','complete='+str(complete),'--project','partial='+str(partial),
                               '--output',str(output),'--repetitions','1','--warmups','0'])
        self.assertEqual(0, code); report = json.loads(output.read_text())
        self.assertFalse(report['allProjectScansComplete']); self.assertTrue(report['passed'])
        self.assertEqual(1, next(case for case in report['cases'] if case['label']=='partial')['aggregate']['incompleteRuns'])
        self.assertGreater(report['cases'][0]['aggregate']['maxPeakRssBytes'], 0)
        self.assertNotIn(str(complete), output.read_text()); self.assertNotIn('PRIVATE', output.read_text()); self.assertFalse(marker.exists())

    def test_explicit_resource_budget_fails_and_output_cannot_modify_scanned_project(self):
        cli = self.fake_cli(); root = self.user/'project'; root.mkdir(); output = self.user/'budget.json'
        with contextlib.redirect_stdout(io.StringIO()):
            code = bench.main(['--cli',str(cli),'--project','fixture='+str(root),'--output',str(output),'--repetitions','1','--warmups','0','--max-peak-rss-mib','1'])
        self.assertEqual(1, code); self.assertIn('RSS_BUDGET', [v['reason'] for v in json.loads(output.read_text())['violations']])
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            bench.main(['--cli',str(cli),'--project','fixture='+str(root),'--output',str(root/'forbidden.json')])
        self.assertFalse((root/'forbidden.json').exists())

    def test_large_acceptance_fixtures_also_enforce_explicit_memory_budget(self):
        cli = self.fake_cli(); output = self.user/'acceptance-budget.json'
        case = {'case': 'generated-regular', 'passed': True, 'processStatus': 'FINISHED', 'measurementComplete': True,
                'elapsedMs': 100, 'peakRssBytes': 50*1024*1024, 'complete': True}
        with patch.object(bench, 'resource_acceptance', return_value=[case]), contextlib.redirect_stdout(io.StringIO()):
            code = bench.main(['--cli',str(cli),'--acceptance','--output',str(output),'--max-peak-rss-mib','1'])
        self.assertEqual(1, code)
        self.assertEqual([{'case':'generated-regular','reason':'RSS_BUDGET'}], json.loads(output.read_text())['violations'])


if __name__ == '__main__': unittest.main()
