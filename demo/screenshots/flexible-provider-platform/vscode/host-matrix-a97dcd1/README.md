# VS Code CI and package equivalence — a97dcd1

Head **`a97dcd19133e9b70b64bc88ae30835822712dbed`**; CI checkout
**`9b48d88d2bd70deb6e8f39f43e4e1dc97f2344c2`**. The six VS Code jobs in
[run 39480826114](https://github.com/Sal7one/NewWrapperGaurd/actions/runs/39480826114)
passed all **48 actual host cases**: eight each on minimum **1.95.0** and current
**1.142.0**, across macOS, Linux and Windows. [Summary](acceptance-summary.json) records
job IDs, immutable artifact IDs and SHA-256 values. The six individual JSON files are
unchanged downloads; each artifact matched GitHub's digest and the expected cases,
editor version, platform and API 1.

[Payload equivalence](vsix-runtime-equivalence.json) compares the CI VSIX with the
[59daa26 package actually installed and tested](../installed-vsix-59daa26/README.md).
Those **eight installed-package cases ran at 59daa26**, on local VS Code 1.116.0; they
were not rerun or relabeled as a new a97 installation test. Of 108 VSIX entries, 106
are byte-identical, including compiled runtime/declarations, package manifest, SAX,
licenses and catalogs/presets. The only changes are:

- README source permalinks: exactly the old commit replaced by the CI checkout commit.
  All five normalized destinations exist in that exact GitHub source tree; fragments
  were checked in the copied CI documents.
- SDK wheel: 38 of 40 members are identical, including all 34 runtime/rule files and
  METADATA, license and entry points. Only `WHEEL` changes its generator from setuptools
  84.2.0 to 84.2.1; only that member's corresponding `RECORD` row changes. Every RECORD
  digest and size was verified. The wheel archives are **not bit-identical**.

CI VSIX SHA-256: `29bc77048c1919c08793ce64818f9b6f1bc7e0b4534409addce6b23857c6dc41`.
CI wheel SHA-256: `2f53a8bc97c2e2300852a5e1b4b64247f70d6f84abac84fb5b7a314d0ecac6eb`.
The report also preserves both accepted baseline hashes. Verify these evidence files
with `shasum -a 256 -c SHA256SUMS` from this directory.

This evidence covers VS Code integration and runtime equivalence, not native IDE
acceptance, formal human accessibility review, physical phones or analyzer accuracy.
