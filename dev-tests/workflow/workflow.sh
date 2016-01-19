#!/bin/sh -e

ls -lrt /opt/workflow/
ls -lrt /opt/workflow/config
ls -lrt /opt/workflow/lib

java -Dconfig.file=/opt/workflow/config/application.conf -jar /opt/workflow/lib/workflow.jar
