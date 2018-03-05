#!/bin/sh -e

cd /opt/woken

wget http://ftp.fau.de/eclipse/tools/aspectj/aspectj-1.8.13.jar

unzip -p aspectj-1.8.13.jar lib/aspectjweaver.jar > aspectjweaver.jar
