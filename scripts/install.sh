#!/usr/bin/env bash
set -euo pipefail

if [[ $EUID -ne 0 ]]; then
  echo "Please run as root" >&2
  exit 1
fi

APP_DIR="/opt/exit-trading"
USER_NAME="trader"
REPO_URL=${1:-""}

apt update
apt install -y openjdk-17-jdk maven unzip

if ! id -u "$USER_NAME" >/dev/null 2>&1; then
  useradd -r -s /bin/false "$USER_NAME"
fi

mkdir -p "$APP_DIR"
chown "$USER_NAME":"$USER_NAME" "$APP_DIR"

if [[ -n "$REPO_URL" ]]; then
  su - "$USER_NAME" -c "git clone $REPO_URL $APP_DIR/src"
fi

echo "Installation complete. Copy kiteconnect.jar to $APP_DIR/lib and build with mvn clean package"
