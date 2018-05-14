#!/bin/sh

/warm-ivy2-cache.sh

TEST="$@"
TEST="${TEST:-$TEST_ARGS}"
TEST="${TEST:-testOnly -- -l org.scalatest.tags.Slow}"

cd /build && sbt "$TEST" -- -Djava.library.path=/lib
