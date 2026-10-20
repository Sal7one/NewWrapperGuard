# Local 253 preflight with IPGP2.18.1

This is a local cached-IDE preflight of the uncommitted patch based on a97dcd1. Compilation and all six real bridge/descriptor cases passed on IDEA253.28294.334 with IPGP2.18.1; no failures/errors/skips. It is **not release-minimum252 compilation or262 runtime proof**. Exact-source CI must still compile against252 and pass the three verifier/runtime targets under the new default verifier failure levels.

The unchanged Kotlin content is delegated from a focused Java ToolWindowFactory entry point. `factory-javap.txt` shows the same public no-argument constructor/content method and no generated inherited internal methods. `minimum-252-interface-javap.txt` was produced from the exact official minimum API artifact, confirming Java defaults for getIcon/getAnchor/manage. The runtime test asserts the factory declares only its public content entry point and inherits whichever defaults the actual target interface provides; future interfaces need not keep removed defaults.

The Gradle log, six-case XML and exact framework resolutions are preserved. JSON records base-source/patch file hashes, artifact identity, counts and evidence hashes. No global verifier exclusions or JVM-default compiler flag changes were introduced.
