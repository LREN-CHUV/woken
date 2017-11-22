#!/bin/sh

/warm-ivy2-cache.sh

cd /build && sbt -Dconfig.file="/build/application.conf" test
