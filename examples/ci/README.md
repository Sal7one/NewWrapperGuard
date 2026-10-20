# Explicit CI and build recipes

All recipes call the installed CLI and its provider API 1. They do not install scanners,
execute project build scripts, download databases or grant providers permissions.
Review the recipe and install a pinned CLI distribution with a verified SHA256 first.
JDK 21 is required. `WRAPPERGUARD_CLI` names its absolute `bin/wrapperguard-cli`
executable (`wrapperguard-cli.bat` on Windows). Each invocation is an explicit BUILD
vetting operation; the CLI does not run the build itself.

| Recipe | Explicit invocation |
|---|---|
| Python | `python examples/ci/check.py /absolute/project` |
| PowerShell | `pwsh -NoProfile -File examples/ci/check.ps1 -ProjectPath /absolute/project` |
| Gradle | Apply the reviewed Kotlin snippet, then `gradle wrapperGuardCheck` |
| Maven | Add the reviewed profile, then `mvn -Pwrapperguard org.codehaus.mojo:exec-maven-plugin:3.5.0:exec` |
| GitHub | Adapt the example workflow after provisioning a pinned verified CLI |

With `--enforce`, exit **0** means this configured scope permitted the operation,
**2** means enforcement blocked it, and **1** means error or incomplete coverage.
All nonzero codes fail these recipes. JSON remains available on stdout for CI artifacts.
An empty or disabled provider set never proves a project secure.

Python, PowerShell and Gradle accept optional `WRAPPERGUARD_PROVIDERS` pointing to
an explicitly reviewed user/CI-owned API 1 provider settings file **outside** the
scanned checkout. Its exact commands and actions are separate authorization.
Never import executable grants from untrusted pull request files, fork-controlled
caches, artifacts or environment values. Maven's base recipe uses built-in static
checks; add provider settings only to trusted runner configuration when needed.
No recipe is attached to build/sync lifecycles automatically. Run the actual build
as a subsequent CI step only after this gate succeeds.
