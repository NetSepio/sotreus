#!/usr/bin/env bash
set -eo pipefail

export WG_DNS=$(host -t A firewall | cut -d " " -f 4)
mkdir -p $WG_KEYS_DIR

echo "Starting sotreus server"
/app/sotreus &
./wg-watcher.sh
sleep infinity