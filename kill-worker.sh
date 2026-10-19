#!/usr/bin/env bash
# The on-stage chaos button: SIGKILL, no graceful shutdown.
cd "$(dirname "$0")"
kill -9 "$(cat .worker.pid)" && echo "worker killed"
