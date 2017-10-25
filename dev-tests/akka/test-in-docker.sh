#!/bin/sh

cd /build && sbt -Dconfig.file="/build/application.conf" test
