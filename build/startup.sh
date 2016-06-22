#!/bin/sh
cd /root/woken/hadrian/hadrian && mvn install
cd /root/woken && sbt assembly
