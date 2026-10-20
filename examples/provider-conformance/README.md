# Provider author conformance fixture

This standalone Python adapter is an inert Provider API 1 fixture. It does not scan,
build or execute a project. It deliberately returns forged host-owned finding fields
so the receiving host must replace them. Five modes cover normal attribution/action
clipping, malformed JSON, a partial result, deadline failure and in-flight cancellation.

From a trusted checkout, compile the TypeScript bridge and run:

```sh
npm --prefix vscode-extension run compile
node vscode-extension/scripts/provider-conformance.js --python /absolute/trusted/python
python scripts/provider-conformance.py --python /absolute/trusted/python
```

The harness copies the adapter into temporary user-controlled storage outside its inert
project, creates explicit registrations, invokes the actual command bridge and removes
its own files. It checks independent BUILD/SYNC action grants, host attribution, baseline
status rejection, redaction, retained partial findings, required timeout blocking and
cancellation after the process has started. It launches no project command.
The JVM CLI harness covers the same response modes; its companion
`SharedProviderConformanceTest` interrupts a running adapter and verifies its PID dies.
The optional `--started-file` marker is atomically written as a decimal process ID.

To exercise the same fixture manually in either editor, copy `adapter.py` outside every
project, choose **Add SDK**, select an absolute Python executable and add literal argv
rows `-I`, `/absolute/installed/adapter.py`, `--mode`, `healthy`. Declare `fixture.txt`
as a project input. Start disabled with warning-only grants, then scan explicitly.
Use a disposable test project; the fixture finding is synthetic and proves no coverage.

Provider authors can adapt these modes to their own transport wrapper and invoke the
harness with `--adapter /absolute/adapter.py`. Keep ordinary analyzer results separate
from failure fixtures. Passing this suite tests protocol and host permissions; it is
not a security audit, detection benchmark, sandbox proof or release certification.
