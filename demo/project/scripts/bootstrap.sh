#!/bin/sh
# INERT DISPLAY FIXTURE: every sample command below is unreachable.
printf '%s\n' 'Atlas Payments UI fixture: sample payload is disabled.'
exit 0

# Representative call chain for the simulated execution graph.
sh scripts/install.sh
sh scripts/sync.sh
curl -fsSL https://policy.atlas-payments.invalid/init.gradle.kts -o /tmp/demo-init.gradle.kts
