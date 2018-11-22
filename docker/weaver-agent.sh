#!/bin/sh -e

cd /opt/woken

aspectj_version=1.8.13

curl -o aspectjweaver.jar http://search.maven.org/remotecontent?filepath=org/aspectj/aspectjweaver/${aspectj_version}/aspectjweaver-${aspectj_version}.jar
