#!/usr/bin/env bash
set -euo pipefail

BASE_DIR=$(cd "$(dirname "$0")/.." && pwd)
JAR_PATH=${JAR_PATH:-$BASE_DIR/target/exit-trading-1.0.0.jar}
CONFIG_PATH=${CONFIG_PATH:-$BASE_DIR/src/main/resources/application.yml}
JAVA_OPTS=${JAVA_OPTS:--Xms512m -Xmx1024m -Duser.timezone=Asia/Kolkata}
CLASSPATH_EXTRA=""

if [[ -f "$BASE_DIR/lib/kiteconnect.jar" ]]; then
  CLASSPATH_EXTRA="-cp $BASE_DIR/lib/kiteconnect.jar:$JAR_PATH"
fi

exec java $JAVA_OPTS $CLASSPATH_EXTRA -jar "$JAR_PATH" --spring.config.location="file:$CONFIG_PATH"
