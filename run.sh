#!/bin/bash

set -e

SCRIPT_DIR="$( cd "$(dirname "$0")" ; pwd -P )"
cd "$SCRIPT_DIR"

export AMORO_HOME="$SCRIPT_DIR"
export AMORO_CONF_DIR="$SCRIPT_DIR/conf"
export AMORO_LOG_DIR="$SCRIPT_DIR/logs"

command="$1"

if [[ "$command" == "cookies" ]]; then
  if [[ -f cookies.txt ]]; then
    rm -f cookies.txt
  fi

  curl -s -X POST http://localhost:1630/api/ams/v1/login \
    -H "Content-Type: application/json" \
    -H "X-Request-Source: Web" \
    -c cookies.txt \
    -d '{"user": "admin", "password": "admin"}'

  echo
  echo "cookies.txt created successfully"
  exit 0
fi

# register the catalog from destination.json
if [[ "$command" == "register" ]]; then
  shift

  DESTINATION=""

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --destination)
        DESTINATION="$2"
        shift 2
        ;;
      *)
        echo "Unknown option: $1"
        exit 1
        ;;
    esac
  done

  if [[ -z "$DESTINATION" ]]; then
    echo "Error: --destination <absolute_path> is required"
    exit 1
  fi
  
  curl -X POST http://localhost:1630/api/ams/v1/catalogs \
    -H "Content-Type: application/json" \
    -H "X-Request-Source: Web" \
    -b cookies.txt \
    -d @$DESTINATION

  echo
  echo "Successfully Registered"
  
  exit 0
fi


./mvnw install -DskipTests -pl amoro-ams -am

LIB_PATH="amoro-ams/target/amoro-ams-dependency/lib"
AMS_JAR="amoro-ams/target/amoro-ams-0.9-SNAPSHOT.jar"
export CLASSPATH="$AMORO_CONF_DIR:$AMS_JAR:$(find $LIB_PATH/ -type f -name "*.jar" | sort | paste -sd':' -)"

echo "AMORO_HOME: $AMORO_HOME"
echo "AMORO_CONF_DIR: $AMORO_CONF_DIR"
echo "Web UI will be available at: http://localhost:1630"
echo "Login: admin/admin"

# run the AmoroServiceContainer
java -Dlog4j.configurationFile=${AMORO_CONF_DIR}/log4j2.xml \
  -Dlog.home=${AMORO_LOG_DIR} \
  -Dlog.dir=${AMORO_LOG_DIR} \
  -Duser.dir=${AMORO_HOME} \
  org.apache.amoro.server.AmoroServiceContainer
