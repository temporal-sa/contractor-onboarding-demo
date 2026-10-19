#!/usr/bin/env bash
# Starts the REST API and front end on http://localhost:8080
cd "$(dirname "$0")"
export DEMO_TIMESCALE="${DEMO_TIMESCALE:-4320}"   # must match the worker
export SWEEP_START_PAUSED="${SWEEP_START_PAUSED:-true}"   # sweep waits for Resume or Trigger now in the UI
[ -f target/contractor-onboarding-demo-0.1.0.jar ] || mvn -q -DskipTests package
exec java -jar target/contractor-onboarding-demo-0.1.0.jar --spring.profiles.active=api
