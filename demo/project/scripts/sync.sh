#!/bin/sh
# INERT DISPLAY FIXTURE: no sync or cache modification will run.
printf '%s\n' 'Atlas Payments UI fixture: sync is disabled.'
exit 0

sh scripts/audit.sh
export DEMO_SHARED_CACHE=../../shared-cache
