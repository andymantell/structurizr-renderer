#!/bin/sh
# ----------------------------------------------------------------------------
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
# ----------------------------------------------------------------------------

# ----------------------------------------------------------------------------
# Apache Maven Wrapper startup script
# ----------------------------------------------------------------------------

if [ -n "$MVNW_DEBUG" ]; then
  set -x
fi

# OS specific support.
cygwin=false
darwin=false
mingw=false
case "$(uname)" in
  CYGWIN*) cygwin=true ;;
  MINGW*)  mingw=true  ;;
  Darwin*) darwin=true ;;
esac

# Detect the base directory of the project
MAVEN_PROJECTBASEDIR="${MAVEN_BASEDIR:-}"
if [ -z "$MAVEN_PROJECTBASEDIR" ]; then
  CURRENT_DIR=$PWD
  while [ ! -d "$CURRENT_DIR/.mvn" ]; do
    cd ..
    if [ "$CURRENT_DIR" = "$(pwd)" ]; then
      MAVEN_PROJECTBASEDIR=$PWD
      break
    fi
    CURRENT_DIR=$(pwd)
  done
  if [ -d "$CURRENT_DIR/.mvn" ]; then
    MAVEN_PROJECTBASEDIR="$CURRENT_DIR"
  fi
  cd "$MAVEN_PROJECTBASEDIR"
fi

# Download maven-wrapper.jar if missing
WRAPPER_JAR="$MAVEN_PROJECTBASEDIR/.mvn/wrapper/maven-wrapper.jar"
WRAPPER_URL=$(grep 'wrapperUrl' "$MAVEN_PROJECTBASEDIR/.mvn/wrapper/maven-wrapper.properties" 2>/dev/null | cut -d= -f2)
WRAPPER_URL="${WRAPPER_URL:-https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.3.2/maven-wrapper-3.3.2.jar}"

if [ ! -e "$WRAPPER_JAR" ]; then
  if command -v wget > /dev/null; then
    wget -q -O "$WRAPPER_JAR" "$WRAPPER_URL" || rm -f "$WRAPPER_JAR"
  elif command -v curl > /dev/null; then
    curl -o "$WRAPPER_JAR" -fsSL "$WRAPPER_URL" || rm -f "$WRAPPER_JAR"
  else
    echo "ERROR: Cannot download Maven Wrapper JAR. Install wget or curl." >&2
    exit 1
  fi
fi

# Find java command
if [ -n "$JAVA_HOME" ]; then
  JAVACMD="$JAVA_HOME/bin/java"
else
  JAVACMD="java"
fi

if ! command -v "$JAVACMD" > /dev/null 2>&1; then
  echo "ERROR: JAVA_HOME is not set and 'java' is not on PATH." >&2
  echo "Install JDK 21 and set JAVA_HOME (or add java to PATH)." >&2
  exit 1
fi

exec "$JAVACMD" \
  ${JVM_CONFIG_MAVEN_PROPS} \
  ${MAVEN_OPTS} \
  -classpath "$WRAPPER_JAR" \
  "-Dmaven.multiModuleProjectDirectory=$MAVEN_PROJECTBASEDIR" \
  org.apache.maven.wrapper.MavenWrapperMain \
  ${MAVEN_CONFIG} \
  "$@"
