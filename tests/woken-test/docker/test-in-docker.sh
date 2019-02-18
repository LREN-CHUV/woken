#!/bin/sh

TEST="$@"
TEST="${TEST:-$TEST_ARGS}"
TEST="${TEST:-testOnly -- -l org.scalatest.tags.Slow}"

cd /build && sbt  "$TEST"
