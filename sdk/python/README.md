# WrapperGuard Python integration SDK

`wrapperguard-sdk` **0.1.0** is an optional Python **3.10+** adapter for WrapperGuard's
existing command-provider API 1. It uses the standard library at runtime.

Supported imports are SARIF 2.1.0, Bandit JSON, PSScriptAnalyzer JSON, Trivy JSON,
OSV-Scanner JSON, and target-scoped ZAP traditional JSON. The fixed Bandit runner is the only
direct scanner runner and currently requires POSIX no-follow file access. Windows can
use report imports. No active DAST scanning or application backend execution exists.
The package neither installs vendor scanners nor replaces WrapperGuard's built-in rules.

From the repository root, install into a trusted environment outside scanned projects:

```sh
python3 -m venv "$HOME/.local/share/wrapperguard/sdk-venv"
"$HOME/.local/share/wrapperguard/sdk-venv/bin/python" -m pip install ./sdk/python
```

Copy a [profile template](../../examples/integrations/README.md) into user configuration
outside the project and replace its absolute paths. Review the plan first:

```sh
"$HOME/.local/share/wrapperguard/sdk-venv/bin/python" -I -m wrapperguard_sdk \
  --profile /absolute/user-config/bandit-report.profile.json \
  --plan --project /absolute/project
```

`wrapperguard-sdk` is the equivalent installed console entry point. Normal operation reads
one host request from stdin and writes one API 1 result to stdout; configure the host to
invoke the installed module rather than running it interactively without a request.
Profiles default to SCAN and WARNING, and the host separately caps all requested actions.
Invalid/disallowed operations fail visibly. Report imports do not prove input freshness.

The [integration guide](../../docs/integrations.md) explains host configuration, SAST/SCA/DAST
coverage, report limits, target scopes, the Bandit runner, and trusted-code boundaries.
Host permissions are not an OS sandbox. The inactive research ML seam remains inactive.

From the repository root:

```sh
"$HOME/.local/share/wrapperguard/sdk-venv/bin/python" -m unittest discover -s sdk/python/tests -v
"$HOME/.local/share/wrapperguard/sdk-venv/bin/python" scripts/sdk-smoke.py \
  --sdk-python "$HOME/.local/share/wrapperguard/sdk-venv/bin/python"
```

The host smoke requires the installed JVM CLI and uses synthetic reports. It does not
execute project sources, run vendor scanners, or contact any DAST target.
The [full verification instructions](../../docs/integrations.md#limits-and-verification)
also cover schema validation, the installed SDK through the TypeScript host, and the
separate real Bandit 1.9.4 check on inert generated sources. The latter requires POSIX;
Bandit and jsonschema are optional test tools rather than SDK runtime dependencies.
