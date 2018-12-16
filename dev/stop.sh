#!/usr/bin/env bash

export HOST="$(ip route get 1 | awk '{print $NF;exit}')"

if groups "$USER" | grep &>/dev/null '\bdocker\b'; then
  DOCKER_COMPOSE="docker-compose"
else
  DOCKER_COMPOSE="sudo -E docker-compose"
fi

$DOCKER_COMPOSE down
$DOCKER_COMPOSE rm -f
