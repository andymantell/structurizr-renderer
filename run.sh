#!/bin/sh
# run.sh — launch structurizr-renderer from bash/sh
# Usage:  ./run.sh path/to/file.dsl [options]
# Options are forwarded directly to the JAR (--help for full list).
set -e

JAR="$(dirname "$0")/target/structurizr-renderer-1.0.0-SNAPSHOT.jar"

if [ ! -f "$JAR" ]; then
  echo "ERROR: JAR not found at $JAR" >&2
  echo "Build first: ./mvnw package -DskipTests" >&2
  exit 1
fi

exec java -jar "$JAR" "$@"
