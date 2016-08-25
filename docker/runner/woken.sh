#!/bin/sh -e

exec java -Dconfig.file=/opt/woken/config/application.conf -jar /opt/woken/woken.jar
