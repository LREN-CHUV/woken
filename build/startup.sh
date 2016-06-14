#!/bin/sh
cd /root/workflow/hadrian/hadrian && mvn install
cd /root/workflow && sbt assembly
