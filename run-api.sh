#!/usr/bin/env bash
# Starts the REST API and front end on http://localhost:8080
cd "$(dirname "$0")"
export DEMO_TIMESCALE="${DEMO_TIMESCALE:-4320}"   # must match the worker
export SWEEP_START_PAUSED="${SWEEP_START_PAUSED:-true}"   # sweep waits for Resume or Trigger now in the UI
# Java 24+ warns at startup when gRPC's bundled Netty uses sun.misc.Unsafe or loads native code.
# The flags only exist from Java 23, so add them only where the warnings appear.
JAVA_OPTS=()
java_major="$(java -XshowSettings:properties -version 2>&1 | awk -F' = ' '/java.specification.version/ {print $2}')"
if [ "${java_major%%.*}" -ge 24 ] 2>/dev/null; then
  JAVA_OPTS+=(--enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow)
fi
[ -f target/contractor-onboarding-demo-0.1.0.jar ] || mvn -q -DskipTests package
exec java "${JAVA_OPTS[@]}" -jar target/contractor-onboarding-demo-0.1.0.jar --spring.profiles.active=api${TEMPORAL_TLS_CLIENT_CERT_PATH:+,mtls}
