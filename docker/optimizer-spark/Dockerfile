#  Licensed to the Apache Software Foundation (ASF) under one
#  or more contributor license agreements.  See the NOTICE file
#  distributed with this work for additional information
#  regarding copyright ownership.  The ASF licenses this file
#  to you under the Apache License, Version 2.0 (the
#  "License"); you may not use this file except in compliance
#  with the License.  You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.

ARG SPARK_VERSION=3.5.8

# Stage 1: pull Java 17 from a known image so we get the exact /opt/java/openjdk path.
# eclipse-temurin always places the JRE at /opt/java/openjdk regardless of arch.
FROM eclipse-temurin:17-jre-jammy AS jdk17

# Stage 2: Spark base + Java 17 overlay
FROM apache/spark:${SPARK_VERSION}

ARG MAVEN_MIRROR=https://repo.maven.apache.org/maven2
ARG OPTIMIZER_JOB=optimizer-job.jar
ARG AWS_VERSION=2.24.12
USER root

# Replace whatever Java the Spark base ships with by copying the exact JRE17 tree.
COPY --from=jdk17 /opt/java/openjdk /opt/java/openjdk

ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"

RUN apt-get update \
    && apt-get install -y wget \
    && rm -rf /var/lib/apt/lists/*

RUN cd $SPARK_HOME/jars \
    && wget ${MAVEN_MIRROR}/software/amazon/awssdk/bundle/${AWS_VERSION}/bundle-${AWS_VERSION}.jar \
    && wget ${MAVEN_MIRROR}/software/amazon/awssdk/url-connection-client/${AWS_VERSION}/url-connection-client-${AWS_VERSION}.jar \
    && chown spark:spark *.jar \
    && mkdir -p $SPARK_HOME/usrlib

COPY $OPTIMIZER_JOB $SPARK_HOME/usrlib/optimizer-job.jar
