#!/usr/bin/env bash

set -e

get_script_dir () {
     SOURCE="${BASH_SOURCE[0]}"

     while [ -h "$SOURCE" ]; do
          DIR="$( cd -P "$( dirname "$SOURCE" )" && pwd )"
          SOURCE="$( readlink "$SOURCE" )"
          [[ $SOURCE != /* ]] && SOURCE="$DIR/$SOURCE"
     done
     cd -P "$( dirname "$SOURCE" )"
     pwd
}

cd "$(get_script_dir)"

if pgrep -lf sshuttle > /dev/null ; then
  echo "sshuttle detected. Please close this program as it messes with networking and prevents Docker links to work"
  exit 1
fi

if groups "$USER" | grep &>/dev/null '\bdocker\b'; then
  DOCKER="docker"
  DOCKER_COMPOSE="docker-compose"
else
  DOCKER="sudo docker"
  DOCKER_COMPOSE="sudo docker-compose"
fi

trap '$DOCKER_COMPOSE rm -f' SIGINT SIGQUIT

echo "Remove old running containers (if any)..."
$DOCKER_COMPOSE kill
$DOCKER_COMPOSE rm -f

echo "Deploy a Postgres instance and wait for it to be ready..."
$DOCKER_COMPOSE up -d db zookeeper mesos_master mesos_slave
$DOCKER_COMPOSE build woken_test
$DOCKER_COMPOSE run wait_dbs

echo "Create databases..."
$DOCKER_COMPOSE run create_dbs

echo "Migrate woken database..."
$DOCKER_COMPOSE run woken_db_setup

echo "Migrate metadata database..."
$DOCKER_COMPOSE run meta_db_setup

echo "Migrate features database..."
$DOCKER_COMPOSE run sample_db_setup

echo "Run containers..."
$DOCKER_COMPOSE run wait_zookeeper
$DOCKER_COMPOSE up -d chronos zipkin zipkin-ui woken woken_validation

$DOCKER_COMPOSE run wait_woken

echo "The Algorithm Factory is now running on your system"

echo
echo "Testing HTTP web services..."

./http/query-experiment.sh

echo
echo "Testing Akka API..."

$DOCKER_COMPOSE run woken_test

echo
echo "Now that's up to you to play with the user interface..."

$DOCKER_COMPOSE up -d portalbackend

$DOCKER_COMPOSE run wait_portal_backend

$DOCKER_COMPOSE up -d frontend
