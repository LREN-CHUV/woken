#!/bin/bash -e

#Wait 5 seconds to let postgresql finish initializing
sleep 5 && java -Dconfig.file=/opt/woken/config/application.conf -DHOST=$HOST -jar /opt/woken/lib/woken.jar

