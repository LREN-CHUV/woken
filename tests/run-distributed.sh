#!/usr/bin/env bash

#
# Start Woken in a distributed mode with its full environment and two worker nodes using different datasets.
#
# Option:
#   --no-tests: skip the test suite
#   --all-tests: execute the full suite of tests, including slow tests such as Chaos testing
#   --no-frontend: do not start the frontend
#

set -o pipefail  # trace ERR through pipes
set -o errtrace  # trace ERR through 'time command' and other functions
set -o errexit   ## set -e : exit the script if any statement returns a non-true return value

get_script_dir () {
     SOURCE="${BASH_SOURCE[0]}"

     while [[ -h "$SOURCE" ]]; do
          DIR="$( cd -P "$( dirname "$SOURCE" )" && pwd )"
          SOURCE="$( readlink "$SOURCE" )"
          [[ $SOURCE != /* ]] && SOURCE="$DIR/$SOURCE"
     done
     cd -P "$( dirname "$SOURCE" )"
     pwd
}

cd "$(get_script_dir)"

frontend=1
tests=1
test_args="testOnly -- -l org.scalatest.tags.Slow"
for param in "$@"
do
  if [[ "--no-frontend" == "$param" ]]; then
    frontend=0
    echo "INFO: --no-frontend option detected !"
  fi
  if [[ "--no-tests" == "$param" ]]; then
    tests=0
    echo "INFO: --no-tests option detected !"
  fi
  if [[ "-&&ll-tests" == "$param" ]]; then
    test_args=""
    echo "INFO: --all-tests option detected !"
  fi
done

if pgrep -lf sshuttle > /dev/null ; then
  echo "sshuttle detected. Please close this program as it messes with networking and prevents Docker links to work"
  exit 1
fi

if [[ $NO_SUDO || -n "$CIRCLECI" ]]; then
  DOCKER_COMPOSE="docker-compose -f docker-compose-federation.yml"
elif groups "$USER" | grep &>/dev/null '\bdocker\b'; then
  DOCKER_COMPOSE="docker-compose -f docker-compose-federation.yml"
else
  DOCKER_COMPOSE="sudo docker-compose -f docker-compose-federation.yml"
fi

function _cleanup() {
  local error_code="$?"
  echo "Stopping the containers..."
  $DOCKER_COMPOSE stop | true
  $DOCKER_COMPOSE down | true
  $DOCKER_COMPOSE rm -f > /dev/null 2> /dev/null | true
  exit $error_code
}
trap _cleanup SIGINT SIGQUIT

export HOST=$(hostname)
export TEST_ARGS="${test_args}"

echo "Remove old running containers (if any)..."
$DOCKER_COMPOSE kill
$DOCKER_COMPOSE rm -f

echo "Deploy a Postgres server and wait for it to be ready..."
$DOCKER_COMPOSE up -d db zookeeper
$DOCKER_COMPOSE run wait_zookeeper
$DOCKER_COMPOSE up -d mesos_master
$DOCKER_COMPOSE run wait_mesos_master
$DOCKER_COMPOSE up -d mesos_slave
if [ $tests == 1 ]; then
    $DOCKER_COMPOSE build wokencentraltest
fi
$DOCKER_COMPOSE run wait_dbs

echo "Create databases..."
$DOCKER_COMPOSE run create_dbs

echo "Migrate woken database..."
$DOCKER_COMPOSE run woken_db_setup
$DOCKER_COMPOSE run woken1_db_setup
$DOCKER_COMPOSE run woken2_db_setup

echo "Migrate metadata database..."
$DOCKER_COMPOSE run sample_meta_db_setup

echo "Migrate features database..."
$DOCKER_COMPOSE run sample_data_db_setup
$DOCKER_COMPOSE run sample_data_db_setup1
$DOCKER_COMPOSE run sample_data_db_setup2

echo "Run containers..."
for i in 1 2 3 4 5 ; do
  $DOCKER_COMPOSE up -d chronos
  $DOCKER_COMPOSE run wait_chronos
  $DOCKER_COMPOSE logs chronos | grep java.util.concurrent.TimeoutException || break
  echo "Chronos failed to start, restarting..."
  $DOCKER_COMPOSE stop chronos
done

$DOCKER_COMPOSE up -d wokennode1 wokennode2
$DOCKER_COMPOSE run wait_wokennode1
$DOCKER_COMPOSE run wait_wokennode2
$DOCKER_COMPOSE up -d wokenvalidationnode1 wokenvalidationnode2
$DOCKER_COMPOSE run wait_wokenvalidationnode1
$DOCKER_COMPOSE run wait_wokenvalidationnode2

$DOCKER_COMPOSE up -d wokencentral
$DOCKER_COMPOSE run wait_wokencentral

for i in 1 2 3 4 5 ; do
  $DOCKER_COMPOSE logs chronos | grep java.util.concurrent.TimeoutException || break
  echo "Chronos failed to start, restarting..."
  $DOCKER_COMPOSE stop chronos
  $DOCKER_COMPOSE up -d chronos
  $DOCKER_COMPOSE run wait_chronos
done

echo "The Algorithm Factory is now running on your system"

if [[ $tests == 1 ]]; then
  echo
  echo "Testing HTTP web services..."

  ./http/query-knn-distributed.sh

  echo
  echo "Testing Akka API..."

  mkdir -p woken-test/target/responses
  $DOCKER_COMPOSE up wokentest ${test_args} | tee test.log

fi

if [[ $frontend == 1 ]]; then
  $DOCKER_COMPOSE up -d portalbackend
  $DOCKER_COMPOSE run wait_portal_backend
  $DOCKER_COMPOSE up -d frontend

  echo ""
  echo "System up!"
  echo "Useful URLs:"
  echo "  http://frontend/ : the Web portal"
  echo "  http://localhost:8080/services/swagger-ui.html : Swagger admin interface for backend"
  echo "  http://localhost:8087 : Swagger admin interface for Woken Central"
  echo "  http://localhost:18087 : Swagger admin interface for Woken Node 1"
  echo "  http://localhost:28087 : Swagger admin interface for Woken Node 2"
fi
