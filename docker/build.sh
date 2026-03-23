#!/usr/bin/env bash
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#  *
#     http://www.apache.org/licenses/LICENSE-2.0
#  *
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Modified by Datazip Inc. in 2026
#

CURRENT_DIR="$( cd "$(dirname "$0")" ; pwd -P )"
PROJECT_HOME="$( cd "$CURRENT_DIR/../" ; pwd -P )"
export PROJECT_HOME

MVN="${PROJECT_HOME}/mvnw"

cd $CURRENT_DIR

AMORO_VERSION=`cat $PROJECT_HOME/pom.xml | grep 'amoro-parent' -C 3 | grep -Eo '<version>.*</version>' | awk -F'[><]' '{print $3}'`
SPARK_VERSION=3.5.8
SCALA_BINARY_VERSION=2.13
DEBIAN_MIRROR=http://deb.debian.org
APACHE_ARCHIVE=https://archive.apache.org/dist
AMORO_TAG=$AMORO_VERSION
MAVEN_MIRROR=https://repo.maven.apache.org/maven2


function usage() {
    cat <<EOF
Usage: $0 [options] [image]
Build Fusion docker images.

Images:
    amoro-spark-optimizer   Build Fusion optimizer deployed with Spark engine for production environments.
    amoro                   Build official Fusion image used for production environments.

Options:
    --spark-version         Spark binary release version, default is 3.5.8, format must be x.y.z
    --scala-binary-version  Scala binary version, default is 2.13
    --apache-archive        Apache Archive url, default is https://archive.apache.org/dist
    --debian-mirror         Mirror url of debian, default is http://deb.debian.org
    --maven-mirror          Mirror url of maven, default is https://repo.maven.apache.org/maven2
    --optimizer-job         Location of spark optimizer job
    --tag                   Tag for amoro/amoro-spark-optimizer image.
EOF
}


ACTION=help

i=1;
j=$#;
while [ $i -le $j ]; do
  case $1 in
    amoro-spark-optimizer|amoro)
    ACTION=$1;
    i=$((i+1))
    shift 1
    ;;

    '--spark-version')
    shift 1
    SPARK_VERSION=$1
    i=$((i+2))
    shift 1
    ;;

    '--scala-binary-version')
    shift 1
    SCALA_BINARY_VERSION=$1
    i=$((i+2))
    shift 1
    ;;

    "--apache-archive")
    shift 1
    APACHE_ARCHIVE=$1
    i=$((i+2))
    shift 1
    ;;

    "--debian-mirror")
    shift 1
    DEBIAN_MIRROR=$1
    i=$((i+2))
    shift 1
    ;;

    "--optimizer-job")
    shift 1
    OPTIMIZER_JOB=$1
    i=$((i+2))
    shift 1
    ;;

    "--tag")
    shift 1
    AMORO_TAG=$1
    i=$((i+2))
    shift 1
    ;;

    "--maven-mirror")
    shift 1
    MAVEN_MIRROR=$1
    i=$((i+2))
    shift 1
    ;;

    *)
      echo "Unknown args of $1"
      usage
      exit 1
    ;;
  esac
done

SPARK_MAJOR_VERSION=${SPARK_VERSION%.*}
SPARK_OPTIMIZER_JOB_PATH=amoro-optimizer/amoro-optimizer-spark/target/amoro-optimizer-spark-${SPARK_MAJOR_VERSION}_${SCALA_BINARY_VERSION}-${AMORO_VERSION}-jar-with-dependencies.jar
SPARK_OPTIMIZER_JOB=${PROJECT_HOME}/${SPARK_OPTIMIZER_JOB_PATH}

function print_env() {
  echo "SET SPARK_VERSION=${SPARK_VERSION}"
  echo "SET SPARK_MAJOR_VERSION=${SPARK_MAJOR_VERSION}"
  echo "SET SCALA_BINARY_VERSION=${SCALA_BINARY_VERSION}"
  echo "SET APACHE_ARCHIVE=${APACHE_ARCHIVE}"
  echo "SET DEBIAN_MIRROR=${DEBIAN_MIRROR}"
  echo "SET AMORO_VERSION=${AMORO_VERSION}"
  echo "SET AMORO_TAG=${AMORO_TAG}"
}

# print_image $IMAGE_NAME $TAG
function print_image() {
   local image=$1
   local tag=$2
   echo "=============================================="
   echo "          $image:$tag               "
   echo "=============================================="
   echo "Start Build ${image}:${tag} Image"
}

function build_optimizer_spark() {
    local IMAGE_REF=apache/amoro-spark-optimizer
    local IMAGE_TAG=$AMORO_TAG-spark${SPARK_MAJOR_VERSION}
    print_image $IMAGE_REF $IMAGE_TAG

    OPTIMIZER_JOB=${SPARK_OPTIMIZER_JOB}

    if [ ! -f "${OPTIMIZER_JOB}" ]; then
      BUILD_CMD="$MVN clean package -pl amoro-optimizer/amoro-optimizer-spark -am -e -DskipTests -Pspark-${SPARK_MAJOR_VERSION}"
      echo "spark optimizer job not exists in ${OPTIMIZER_JOB}"
      echo "please check the file or run '${BUILD_CMD}' first. "
      exit  1
    fi

    set -x
    cd "$PROJECT_HOME" || exit
    docker build -t ${IMAGE_REF}:${IMAGE_TAG} \
      --build-arg SPARK_VERSION=$SPARK_VERSION \
      --build-arg OPTIMIZER_JOB=$SPARK_OPTIMIZER_JOB_PATH \
      --build-arg MAVEN_MIRROR=$MAVEN_MIRROR \
      -f ./docker/optimizer-spark/Dockerfile .
}

function build_amoro() {
  local IMAGE_REF=apache/amoro
  local IMAGE_TAG=$AMORO_TAG
  print_image $IMAGE_REF $IMAGE_TAG

  local DIST_FILE=${PROJECT_HOME}/dist/target/apache-amoro-${AMORO_VERSION}-bin.tar.gz

  if [ ! -f "${DIST_FILE}" ]; then
    local BUILD_CMD="$MVN clean package -am -e -pl dist -DskipTests -Pspark-${SPARK_MAJOR_VERSION}"
    echo "Amoro dist package is not exists in ${DIST_FILE}"
    echo "please check file or run '$BUILD_CMD' first"
    exit 1
  fi

  set -x
  cd "$PROJECT_HOME" || exit
  docker build -t ${IMAGE_REF}:${IMAGE_TAG} \
    --build-arg MAVEN_MIRROR=$MAVEN_MIRROR \
    -f docker/amoro/Dockerfile .
  return $?
}

case "$ACTION" in
  amoro-spark-optimizer)
    print_env
    build_optimizer_spark
    ;;
  amoro)
    print_env
    build_amoro
    ;;
  *)
    echo "Unknown image type: $ACTION"
    exit 1
    ;;
esac
