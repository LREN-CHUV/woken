#!/bin/sh -e

ls -lrt /opt/workflow/
ls -lrt /opt/workflow/config
ls -lrt /opt/workflow/lib

echo "Waiting for the databases to start on $HOST:5432 and $HOST:65432 ..."
/usr/local/bin/dockerize -timeout 240s -wait tcp://$HOST:5432 -wait tcp://$HOST:65432 java -Dconfig.file=/opt/workflow/config/application.conf -DHOST=$HOST -jar /opt/workflow/lib/workflow.jar
