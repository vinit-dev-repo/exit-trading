#!/usr/bin/env bash
set -euo pipefail

BASE_DIR=$(cd "$(dirname "$0")/.." && pwd)
JAR_PATH=${JAR_PATH:-$BASE_DIR/target/exit-trading-1.0.0.jar}
CONFIG_PATH=${CONFIG_PATH:-$BASE_DIR/src/main/resources/application.yml}
JAVA_OPTS=${JAVA_OPTS:--Xms512m -Xmx1024m -Duser.timezone=Asia/Kolkata}
LOADER_PATH=""

if [[ -f "$BASE_DIR/lib/kiteconnect.jar" ]]; then
  LOADER_PATH="--loader.path=$BASE_DIR/lib/"
fi

exec java $JAVA_OPTS -jar "$JAR_PATH" $LOADER_PATH --spring.config.location="file:$CONFIG_PATH"
