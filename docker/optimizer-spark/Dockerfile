ARG SPARK_VERSION=3.5.7
FROM apache/spark:${SPARK_VERSION}

ARG MAVEN_MIRROR=https://repo.maven.apache.org/maven2
ARG OPTIMIZER_JOB=optimizer-job.jar
ARG AWS_VERSION=2.24.12

USER root

RUN apt-get update \
    && apt-get install -y wget

RUN cd $SPARK_HOME/jars \
    && wget ${MAVEN_MIRROR}/software/amazon/awssdk/bundle/${AWS_VERSION}/bundle-${AWS_VERSION}.jar \
    && wget ${MAVEN_MIRROR}/software/amazon/awssdk/url-connection-client/${AWS_VERSION}/url-connection-client-${AWS_VERSION}.jar \
    && chown spark:spark *.jar \
    && mkdir -p $SPARK_HOME/usrlib

COPY $OPTIMIZER_JOB $SPARK_HOME/usrlib/optimizer-job.jar

# Create log directories and set permission
RUN mkdir -p /opt/spark/logs/history \
    && chown -R spark:spark /opt/spark/logs

# Copy optimizer log4j2 configuration
RUN mkdir -p /opt/spark/custom-conf
COPY log4j2.xml /opt/spark/custom-conf/log4j2.xml
RUN mkdir -p /opt/spark/conf && \
    echo "spark.driver.extraJavaOptions=-Dlog4j.configurationFile=/opt/spark/custom-conf/log4j2.xml" >> /opt/spark/conf/spark.properties && \
    echo "spark.executor.extraJavaOptions=-Dlog4j.configurationFile=/opt/spark/custom-conf/log4j2.xml" >> /opt/spark/conf/spark.properties