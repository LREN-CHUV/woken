#!/usr/bin/env bash

set -e

if pgrep -lf sshuttle > /dev/null ; then
  echo "sshuttle detected. Please close this program as it messes with networking and prevents docker-compose to work"
  exit 1
fi

if groups "$USER" | grep &>/dev/null '\bdocker\b'; then
  DOCKER="docker"
  DOCKER_COMPOSE="docker-compose"
else
  DOCKER="sudo docker"
  DOCKER_COMPOSE="sudo docker-compose"
fi

function _cleanup() {
  local error_code="$?"
  echo "Stopping the Docker containers..."
  $DOCKER_COMPOSE stop
  $DOCKER_COMPOSE rm -f
  exit ${error_code}
}
trap _cleanup EXIT INT TERM

$DOCKER network inspect woken-bridge 2>&1 > /dev/null || $DOCKER network create woken-bridge

$DOCKER_COMPOSE up -d zookeeper1 mesos_master mesos_slave chronos db
$DOCKER_COMPOSE run wait_dbs
echo "Setting up the databases..."
$DOCKER_COMPOSE run create_dbs
$DOCKER_COMPOSE run meta_db_setup
$DOCKER_COMPOSE run features_db_setup
$DOCKER_COMPOSE run woken_db_setup
$DOCKER_COMPOSE up -d woken
echo "Woken up and running..."
