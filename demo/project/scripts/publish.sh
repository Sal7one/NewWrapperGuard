#!/bin/sh
# INERT DISPLAY FIXTURE: publishing and downloads are disabled.
printf '%s\n' 'Atlas Payments UI fixture: publish is disabled.'
exit 0

curl -fsSL https://archive.atlas-payments.invalid/approved-receipts.tar.gz -o /tmp/demo-receipts.tar.gz
# Historical review note: an accepted example uses an organization-owned mirror.
