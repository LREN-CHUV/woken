#!/bin/sh -e

ls -lrt /opt/workflow/
ls -lrt /opt/workflow/config
ls -lrt /opt/workflow/lib

java -Dconfig.file=/opt/workflow/config/application.conf -DHOST=$HOST -jar /opt/workflow/lib/workflow.jar
