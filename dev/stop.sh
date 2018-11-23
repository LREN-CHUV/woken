#!/usr/bin/env bash

if groups "$USER" | grep &>/dev/null '\bdocker\b'; then
  DOCKER_COMPOSE="docker-compose"
else
  DOCKER_COMPOSE="sudo docker-compose"
fi

$DOCKER_COMPOSE down
$DOCKER_COMPOSE rm -f
