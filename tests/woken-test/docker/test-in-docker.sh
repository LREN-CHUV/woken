#!/bin/sh

/warm-ivy2-cache.sh

TEST="$@"
TEST="${TEST:-test}"

cd /build && sbt -Dconfig.file="/build/application.conf" "$TEST"
