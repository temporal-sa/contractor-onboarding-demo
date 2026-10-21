#!/usr/bin/env bash
# Starts the Temporal worker as its own process. exec keeps the PID, so kill-worker.sh can find it.
cd "$(dirname "$0")"
export DEMO_TIMESCALE="${DEMO_TIMESCALE:-4320}"   # 1 day = ~20 seconds
# Java 24+ warns at startup when gRPC's bundled Netty uses sun.misc.Unsafe or loads native code.
# The flags only exist from Java 23, so add them only where the warnings appear.
JAVA_OPTS=()
java_major="$(java -XshowSettings:properties -version 2>&1 | awk -F' = ' '/java.specification.version/ {print $2}')"
if [ "${java_major%%.*}" -ge 24 ] 2>/dev/null; then
  JAVA_OPTS+=(--enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow)
fi
[ -f target/contractor-onboarding-demo-0.1.0.jar ] || mvn -q -DskipTests package
echo $$ > .worker.pid
exec java "${JAVA_OPTS[@]}" -jar target/contractor-onboarding-demo-0.1.0.jar --spring.profiles.active=worker${TEMPORAL_TLS_CLIENT_CERT_PATH:+,mtls}
