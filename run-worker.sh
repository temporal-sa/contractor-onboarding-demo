#!/usr/bin/env bash
# Starts the Temporal worker as its own process. exec keeps the PID, so kill-worker.sh can find it.
cd "$(dirname "$0")"
export DEMO_TIMESCALE="${DEMO_TIMESCALE:-4320}"   # 1 day = ~20 seconds
[ -f target/contractor-onboarding-demo-0.1.0.jar ] || mvn -q -DskipTests package
echo $$ > .worker.pid
exec java -jar target/contractor-onboarding-demo-0.1.0.jar --spring.profiles.active=worker
