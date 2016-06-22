#!/bin/sh -e

ls -lrt /opt/woken/
ls -lrt /opt/woken/config
ls -lrt /opt/woken/lib

sleep 150

echo "Waiting for the databases to start on $HOST:5432 and $HOST:65432 ..."
/usr/local/bin/dockerize -timeout 240s -wait tcp://$HOST:5432 -wait tcp://$HOST:65432 java -Dconfig.file=/opt/woken/config/application.conf -DHOST=$HOST -jar /opt/woken/lib/woken.jar
