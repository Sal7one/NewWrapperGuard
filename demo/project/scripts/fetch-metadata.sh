#!/bin/sh
# INERT DISPLAY FIXTURE: this example performs no requests.
printf '%s\n' 'Atlas Payments UI fixture: metadata fetch is disabled.'
exit 0

curl -fsSL https://metadata.atlas-payments.invalid/registry.json
sh scripts/audit.sh
