"""Trusted container bootstrap: drop worker identity before reading any input code.

The separate UID0 timeout retains only KILL/SETUID/SETGID. This worker irreversibly
drops groups and all real/effective/saved IDs to65532; no-new-privileges prevents
regaining image file capabilities. Input workers cannot signal the UID0 timer.
"""
import os
import sys


def _status():
    with open('/proc/self/status', encoding='ascii') as stream:
        return dict(line.split(':', 1) for line in stream if ':' in line)


def main():
    try:
        if (sys.platform != 'linux' or os.environ.get('WRAPPERGUARD_CONTAINER_AGENT') != '1' or
                os.getresuid() != (0, 0, 0) or len(sys.argv) < 2 or len(sys.argv) > 130 or
                not sys.argv[1].startswith('/')):
            raise ValueError('Explicit trusted container bootstrap required')
        before = _status()
        if (before.get('NoNewPrivs', '').strip() != '1' or
                int(before.get('CapEff', '0'), 16) != 0xE0):  # KILL,SETGID,SETUID only.
            raise ValueError('Trusted timer capabilities do not match policy')
        os.setgroups([])
        os.setresgid(65532, 65532, 65532)
        os.setresuid(65532, 65532, 65532)
        after = _status()
        if (os.getresuid() != (65532,)*3 or os.getresgid() != (65532,)*3 or os.getgroups() or
                after.get('NoNewPrivs', '').strip() != '1' or
                any(int(after.get(key, '1'), 16) != 0 for key in ('CapEff', 'CapPrm', 'CapAmb'))):
            raise ValueError('Worker privilege drop could not be verified')
        # Only now may the selected scanner or dynamic observation agent start.
        os.execv(sys.argv[1], sys.argv[1:])
    except (OSError, ValueError, RuntimeError):
        print('Container worker privilege boundary could not be established', file=sys.stderr)
        return 1


if __name__ == '__main__': raise SystemExit(main())
