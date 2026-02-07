#!/usr/bin/env sh
DIR="$(cd "$(dirname "$0")" && pwd)"
JAVA_OPTS=""
exec "$DIR/gradle/wrapper/gradle-wrapper.jar" "$@"
