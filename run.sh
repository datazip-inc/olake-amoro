#!/bin/bash
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Quick script to run AMS for local development
#

set -e

SCRIPT_DIR="$( cd "$(dirname "$0")" ; pwd -P )"
cd "$SCRIPT_DIR"

export AMORO_HOME="$SCRIPT_DIR"
export AMORO_CONF_DIR="$SCRIPT_DIR/conf"
export AMORO_LOG_DIR="$SCRIPT_DIR/logs"


./mvnw install -DskipTests -pl amoro-ams -am

LIB_PATH="amoro-ams/target/amoro-ams-dependency/lib"
AMS_JAR="amoro-ams/target/amoro-ams-0.9-SNAPSHOT.jar"
export CLASSPATH="$AMORO_CONF_DIR:$AMS_JAR:$(find $LIB_PATH/ -type f -name "*.jar" | sort | paste -sd':' -)"

echo "AMORO_HOME: $AMORO_HOME"
echo "AMORO_CONF_DIR: $AMORO_CONF_DIR"
echo "Web UI will be available at: http://localhost:1630"
echo "Login: admin/admin"

java -Dlog4j.configurationFile=${AMORO_CONF_DIR}/log4j2.xml \
  -Dlog.home=${AMORO_LOG_DIR} \
  -Dlog.dir=${AMORO_LOG_DIR} \
  -Duser.dir=${AMORO_HOME} \
  org.apache.amoro.server.AmoroServiceContainer
