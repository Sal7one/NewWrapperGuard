from dataclasses import replace
import json
import os
from pathlib import Path
import socket
import stat
import tempfile
import time
import unittest
from unittest.mock import patch

from wrapperguard_sdk.cli import evaluate, build_plan
from wrapperguard_sdk.container_agent import observe
from wrapperguard_sdk.container_worker import main as worker_main
from wrapperguard_sdk.profile import load_profile
from wrapperguard_sdk.sandbox import run_container


@unittest.skipUnless(os.name == 'posix', 'local Unix socket sandbox backend')
class SandboxTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.user = Path(self.temp.name).resolve(); self.root = self.user / 'project'; self.root.mkdir()
        self.marker = self.user / 'executed-on-host'
        (self.root / 'payload.py').write_text('from pathlib import Path\nPath('+repr(str(self.marker))+').touch()\n')
        (self.root / 'secret.txt').write_text('PRIVATE INPUT NOT SELECTED')
        self.engine = self.user / 'docker'; self.engine.write_text('#!/bin/sh\nexit 99\n'); self.engine.chmod(0o700)
        self.socket_path = self.user / 'engine.sock'; self.socket = socket.socket(socket.AF_UNIX)
        self.socket.bind(str(self.socket_path)); self.addCleanup(self.socket.close)
        self.image = 'sha256:'+'a'*64; self.calls = []; self.rootless = True; self.volumes = None
        self.output = b'{"results":[],"errors":[],"metrics":{}}'; self.failure = None; self.retained = False
        self.value = {'id': 'sandbox.user', 'displayName': 'Reviewed sandbox', 'version': '1', 'analysisKind': 'SAST',
            'format': 'bandit', 'runner': {'tool': 'bandit', 'executable': '/usr/local/bin/bandit', 'timeoutMs': 30000,
            'sandbox': {'engine': 'docker', 'executable': str(self.engine), 'endpoint': str(self.socket_path), 'image': self.image}},
            'requestedActions': ['BLOCK_BUILD', 'WARNING']}

    def load(self):
        path = self.user / 'profile.json'; path.write_text(json.dumps(self.value)); return load_profile(path, self.root)

    def fake_engine(self, argv, cwd, environment, deadline, maximum, accepted_codes=(0,), **kwargs):
        self.calls.append((argv, cwd, environment, deadline, maximum, accepted_codes, kwargs))
        self.assertEqual([str(self.engine), '--host', 'unix://'+str(self.socket_path)], argv[:3])
        arguments = argv[3:]
        if arguments[0] == 'info':
            return json.dumps({'os': 'linux', 'security': ['name=seccomp,profile=builtin'] + (['name=rootless'] if self.rootless else []), 'version': '29.0.0',
                'limits': {'memory': True, 'swap': True, 'cpu': True, 'pids': True, 'cgroup': '2', 'driver': 'systemd'}}).encode()
        if arguments[:2] == ['image', 'inspect']:
            return json.dumps({'id': self.image, 'digests': [], 'volumes': self.volumes}).encode()
        if arguments[0] == 'run':
            self.assertEqual(0o700, stat.S_IMODE(cwd.stat().st_mode), 'Outer temporary root must stay private')
            self.assertEqual(0o400, stat.S_IMODE((cwd/'source'/'payload.py').stat().st_mode), 'Original snapshot modes must be preserved')
            mount = Path(arguments[arguments.index('--mount')+1].split(',src=',1)[1].split(',dst=',1)[0])
            self.assertEqual(cwd/'container-inputs', mount)
            self.assertEqual(0o755, stat.S_IMODE(mount.stat().st_mode))
            self.assertEqual(0o444, stat.S_IMODE((mount/'source'/'payload.py').stat().st_mode))
            self.assertFalse((mount/'engine-client').exists(), 'Engine client state must stay outside bind inputs')
            self.assertEqual((cwd/'source'/'payload.py').read_bytes(), (mount/'source'/'payload.py').read_bytes())
            if self.failure: raise self.failure
            return self.output
        if arguments[0] == 'rm': return b''
        if arguments[0] == 'ps': return b'retained-container\n' if self.retained else b''
        self.fail('Unexpected engine command')

    def test_static_container_has_fixed_isolation_no_host_secrets_and_only_owned_readonly_snapshot(self):
        with patch.dict(os.environ, {'DOCKER_HOST': 'tcp://attacker:2375', 'DOCKER_CONFIG': '/private/credentials', 'TOKEN': 'secret'}), patch('wrapperguard_sdk.sandbox.bounded_process', self.fake_engine):
            result = evaluate(self.load(), self.root, 'SCAN')
        self.assertTrue(result['complete']); self.assertFalse(self.marker.exists())
        call = next(call for call in self.calls if call[0][3] == 'run'); args, owned, environment = call[:3]
        for expected in ('--read-only', '--network', 'none', '--cap-drop', 'ALL', 'no-new-privileges=true', '0:0', '--pids-limit', '--memory', '--memory-swap', '--cpus', '--no-healthcheck', '--rm', '--pull', 'never'):
            self.assertIn(expected, args)
        self.assertEqual('/usr/bin/timeout', args[args.index('--entrypoint')+1])
        self.assertEqual(['KILL', 'SETUID', 'SETGID'], [args[i+1] for i,a in enumerate(args[:-1]) if a == '--cap-add'])
        self.assertIn('/workspace/policy/container_worker.py', args)
        self.assertEqual(1, args.count('--mount')); self.assertIn('dst=/workspace,readonly,bind-propagation=rprivate', args[args.index('--mount')+1])
        self.assertNotIn(str(self.root), ' '.join(args)); self.assertNotIn('secret', json.dumps(environment))
        self.assertNotIn('/private/credentials', json.dumps(environment)); self.assertNotIn('DOCKER_HOST', environment)
        self.assertTrue(Path(environment['DOCKER_CONFIG']).is_relative_to(owned))
        self.assertIn('/workspace/bandit.ini', args); self.assertIn('/usr/local/bin/bandit', args)
        self.assertTrue(any(call[0][3] == 'rm' for call in self.calls)); self.assertEqual('ps', self.calls[-1][0][3])

    def test_rootful_daemon_implicit_image_volumes_and_missing_digest_fail_before_payload_start(self):
        profile = self.load(); self.rootless = False
        with patch('wrapperguard_sdk.sandbox.bounded_process', self.fake_engine), self.assertRaises(ValueError): evaluate(profile, self.root, 'SCAN')
        self.assertFalse(any(call[0][3] == 'run' for call in self.calls))
        self.value['runner']['sandbox']['allowRootfulDaemon'] = True; self.volumes = {'/implicit': {}}
        with patch('wrapperguard_sdk.sandbox.bounded_process', self.fake_engine), self.assertRaises(ValueError): evaluate(self.load(), self.root, 'SCAN')
        self.assertFalse(any(call[0][3] == 'run' for call in self.calls))
        for change in ({'image': 'python:latest'}, {'endpoint': 'tcp://remote:2375'}, {'memoryMiB': True}, {'privileged': True}):
            original = self.value['runner']['sandbox'].copy(); self.value['runner']['sandbox'].update(change)
            with self.assertRaises(ValueError): self.load()
            self.value['runner']['sandbox'] = original

    def test_timeout_partial_start_cancellation_and_unverified_cleanup_fail_closed(self):
        for error in (TimeoutError('bounded tool timeout'), InterruptedError('cancelled'), ValueError('Tool failed')):
            self.calls.clear(); self.failure = error
            with patch('wrapperguard_sdk.sandbox.bounded_process', self.fake_engine), self.assertRaises(type(error)): evaluate(self.load(), self.root, 'SCAN')
            self.assertEqual(['rm', 'ps'], [call[0][3] for call in self.calls[-2:]])
            self.assertFalse(self.marker.exists())
        self.failure = None; self.retained = True
        with patch('wrapperguard_sdk.sandbox.bounded_process', self.fake_engine), self.assertRaisesRegex(ValueError, 'cleanup'): evaluate(self.load(), self.root, 'SCAN')

    def test_dynamic_confirmed_entry_is_never_executed_on_host_and_has_no_enforcement_authority(self):
        self.value['format'] = 'container-python'
        self.value['runner'].update({'tool': 'container-python', 'executable': '/usr/local/bin/python',
                                    'entryPath': 'payload.py', 'executionConfirmed': True, 'executionTimeoutMs': 500})
        self.output = b'{"schemaVersion":1,"events":{"process-attempt":1,"dynamic-code":2},"executionStatus":"finished","complete":true}'
        with patch('wrapperguard_sdk.sandbox.bounded_process', self.fake_engine): result = evaluate(self.load(), self.root, 'SCAN')
        self.assertTrue(result['complete']); self.assertFalse(self.marker.exists())
        self.assertEqual(2, len(result['findings']))
        self.assertTrue(all(f['severity'] == 'INFO' and f['kind'] == 'AUDIT_EVIDENCE' and f['actions'] == [] and f['location']['path'] == 'payload.py' for f in result['findings']))
        plan = build_plan(self.load(), self.root); self.assertTrue(plan['executesProjectCode']); self.assertTrue(plan['advisoryOnly'])
        self.output = b'{"schemaVersion":1,"events":{"untrusted-token-secret":1},"executionStatus":"finished","complete":true}'
        with patch('wrapperguard_sdk.sandbox.bounded_process', self.fake_engine), self.assertRaises(ValueError): evaluate(self.load(), self.root, 'SCAN')

    def test_dynamic_without_container_confirmation_or_relative_entry_never_starts(self):
        self.value['format'] = 'container-python'
        self.value['runner'].update({'tool': 'container-python', 'entryPath': 'payload.py', 'executionConfirmed': True})
        for change in ({'executionConfirmed': False}, {'entryPath': '../payload.py'}, {'entryPath': '/payload.py'}, {'executionTimeoutMs': 99999}):
            original = self.value['runner'].copy(); self.value['runner'].update(change)
            with self.assertRaises(ValueError): self.load()
            self.value['runner'] = original
        self.value['runner'].pop('sandbox')
        with self.assertRaises(ValueError): self.load()
        self.assertFalse(self.marker.exists())

    def test_container_agent_refuses_host_execution_before_opening_payload(self):
        with patch.dict(os.environ, {'WRAPPERGUARD_CONTAINER_AGENT': '1'}), self.assertRaises(ValueError):
            observe(self.root / 'payload.py', 500)
        self.assertFalse(self.marker.exists())
        with patch.dict(os.environ, {'WRAPPERGUARD_CONTAINER_AGENT': '1'}), patch('sys.argv', ['worker', str(self.root/'payload.py')]):
            self.assertEqual(1, worker_main())
        self.assertFalse(self.marker.exists())

    def test_engine_without_enforced_cgroup_limits_cannot_start_payload(self):
        original = self.fake_engine
        def unsupported(*args, **kwargs):
            raw = original(*args, **kwargs)
            if args[0][3] == 'info':
                data = json.loads(raw); data['limits']['memory'] = False; return json.dumps(data).encode()
            return raw
        with patch('wrapperguard_sdk.sandbox.bounded_process', unsupported), self.assertRaisesRegex(ValueError, 'cannot enforce'):
            evaluate(self.load(), self.root, 'SCAN')
        self.assertFalse(any(call[0][3] == 'run' for call in self.calls))


if __name__ == '__main__': unittest.main()
