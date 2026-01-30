#!/bin/sh
set -eu

BASE_URL="http://amoro:1630"
HDR_SOURCE="X-Request-Source: Web"
COOKIE_JAR="/tmp/amoro-cookies.txt"

while true; do
  echo "Waiting for Amoro to be reachable..."
  until curl -sf "${BASE_URL}/" >/dev/null 2>&1; do
    sleep 1
  done

  echo "Logging into Amoro..."
  curl -sf -c "${COOKIE_JAR}" \
    -X POST "${BASE_URL}/api/ams/v1/login" \
    -H "Content-Type: application/json" \
    -H "${HDR_SOURCE}" \
    -d '{"user":"admin","password":"admin"}' >/dev/null

  # Wait for yarnContainer to be registered
  echo "Waiting for yarnContainer to be registered..."
  until curl -sf -b "${COOKIE_JAR}" -H "${HDR_SOURCE}" "${BASE_URL}/api/ams/v1/optimize/containers/get" | grep -q 'yarnContainer'; do
    sleep 1
  done

  # Create 'yarn' optimizer group
  echo "Ensuring optimizer group 'yarn' exists with YARN configuration..."
  curl -sf -b "${COOKIE_JAR}" \
    -X PUT "${BASE_URL}/api/ams/v1/optimize/resourceGroups" \
    -H "Content-Type: application/json" \
    -H "${HDR_SOURCE}" \
    -d '{"name":"yarn","container":"yarnContainer","properties":{"memory":"2048"}}' >/dev/null

  # Check for 'yarn' group
  echo "Ensuring at least one RUNNING YARN optimizer exists..."
  opt_json="$(curl -sf -b "${COOKIE_JAR}" -H "${HDR_SOURCE}" "${BASE_URL}/api/ams/v1/optimize/optimizerGroups/all/optimizers?page=1&pageSize=50")"
  if echo "$opt_json" | grep -q '"groupName":"yarn"'; then
    if echo "$opt_json" | grep -q '"groupName":"yarn".*"jobStatus":"RUNNING"'; then
      echo "YARN optimizer is RUNNING."
    else
      echo "YARN optimizer record exists but not RUNNING; scaling out (parallelism=1)..."
      curl -sf -b "${COOKIE_JAR}" \
        -X POST "${BASE_URL}/api/ams/v1/optimize/optimizerGroups/yarn/optimizers" \
        -H "Content-Type: application/json" \
        -H "${HDR_SOURCE}" \
        -d '{"parallelism":1}' >/dev/null || true
    fi
  else
    echo "No optimizer found for group 'yarn'; scaling out (parallelism=1)..."
    curl -sf -b "${COOKIE_JAR}" \
      -X POST "${BASE_URL}/api/ams/v1/optimize/optimizerGroups/yarn/optimizers" \
      -H "Content-Type: application/json" \
      -H "${HDR_SOURCE}" \
      -d '{"parallelism":1}' >/dev/null || true
  fi

  sleep 15
done
