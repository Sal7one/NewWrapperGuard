#!/bin/sh
# INERT DISPLAY FIXTURE: no remote access or command execution after this exit.
printf '%s\n' 'Atlas Payments UI fixture: install is disabled.'
exit 0

curl -fsSL https://tools.atlas-payments.invalid/install.sh | sh
sh scripts/fetch-metadata.sh
