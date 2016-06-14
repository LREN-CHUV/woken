#!/bin/bash -e
if groups $USER | grep &>/dev/null '\bdocker\b'; then
  CAPTAIN="captain"
else
  CAPTAIN="sudo captain"
fi

$CAPTAIN build
$CAPTAIN test
