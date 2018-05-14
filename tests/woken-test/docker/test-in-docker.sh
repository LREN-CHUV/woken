#!/bin/sh

/warm-ivy2-cache.sh

TEST="$@"
TEST="${TEST:-$TEST_ARGS}"
TEST="${TEST:-testOnly -- -l org.scalatest.tags.Slow -Djava.library.path=/lib}"

cd /build && sbt "$TEST"
