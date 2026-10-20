"""Static Bandit fixture. Never execute; the illustrative call is unreachable."""
raise SystemExit("Scanner test input only; open WrapperGuard and scan this folder.")

import subprocess
subprocess.run("echo scanner-test", shell=True)  # nosec: repository text cannot approve this finding
