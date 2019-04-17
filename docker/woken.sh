#!/bin/bash -e

JAVA="java"

if [ -n "$TRACE" ]; then
  apk add --update --no-cache strace
  JAVA="strace java"
fi

exec ${JAVA} ${JAVA_OPTIONS} -javaagent:/opt/woken/aspectjweaver.jar \
          -Djava.library.path=/lib \
          -Dconfig.file=/opt/woken/config/application.conf \
          -DLog4jContextSelector=org.apache.logging.log4j.core.async.AsyncLoggerContextSelector \
          -Daeron.term.buffer.length=100m \
          -Dscala.concurrent.context.minThreads=${THREADS_MIN:-1} \
          -Dscala.concurrent.context.numThreads=${THREADS_TARGET:-2} \
          -Dscala.concurrent.context.maxThreads=${THREADS_MAX:-5} \
          -jar /opt/woken/woken.jar
