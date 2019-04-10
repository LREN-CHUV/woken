#!/usr/bin/env bash

#
# Start the environment for Woken: Mesos, Chronos, Postgres database with data, Woken validation, Portal frontend.
#
# Option:
#   --no-validation: do not start Woken validation
#   --no-frontend: do not start the frontend
#

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

validation=1
frontend=1
for param in "$@"
do
  if [ "--no-frontend" == "$param" ]; then
    frontend=0
    echo "INFO: --no-frontend option detected !"
  fi
  if [ "--no-validation" == "$param" ]; then
    validation=0
    echo "INFO: --no-validation option detected !"
  fi
done

if pgrep -lf sshuttle > /dev/null ; then
  echo "sshuttle detected. Please close this program as it messes with networking and prevents Docker links to work"
  exit 1
fi

export HOST="$(ip route get 1 | awk '{print $NF;exit}')"

if groups "$USER" | grep &>/dev/null '\bdocker\b'; then
  DOCKER="docker"
  DOCKER_COMPOSE="docker-compose"
else
  DOCKER="sudo -E docker"
  DOCKER_COMPOSE="sudo -E docker-compose"
fi

trap '$DOCKER_COMPOSE rm -f' SIGINT SIGQUIT

echo "Remove old running containers (if any)..."
$DOCKER_COMPOSE kill
$DOCKER_COMPOSE rm -f

echo "Deploy a Postgres server and wait for it to be ready..."
$DOCKER_COMPOSE up -d db zookeeper
$DOCKER_COMPOSE run wait_zookeeper
$DOCKER_COMPOSE up -d mesos_master
$DOCKER_COMPOSE run wait_mesos_master
$DOCKER_COMPOSE up -d mesos_slave
$DOCKER_COMPOSE run wait_dbs

echo "Create databases..."
$DOCKER_COMPOSE run create_dbs

echo "Migrate woken database..."
$DOCKER_COMPOSE run woken_db_setup

echo "Migrate metadata database..."
$DOCKER_COMPOSE run sample_meta_db_setup

echo "Migrate features database..."
$DOCKER_COMPOSE run sample_data_db_setup

echo "Run containers..."
for i in 1 2 3 4 5 ; do
  $DOCKER_COMPOSE up -d chronos
  $DOCKER_COMPOSE run wait_chronos
  $DOCKER_COMPOSE logs chronos | grep java.util.concurrent.TimeoutException || break
  echo "Chronos failed to start, restarting..."
  $DOCKER_COMPOSE stop chronos
done

echo "Please start Woken from your IDE. It should use the configuration in dev/config/application.conf"
echo "and have environment variable CLUSTER_IP set to $(hostname)"
echo "For IntelliJ IDEA, the Run configuration should include:"
echo "  VM Options: -Dconfig.file=config/application.conf"
echo "  Working directory: $(get_script_dir)"
echo "  Environment variables:"
echo "      CLUSTER_PORT: 8088"
echo "      CLUSTER_IP: $(hostname)"
echo "      WOKEN_PORT_8088_TCP_ADDR: $(hostname)
echo "      DOCKER_BRIDGE_NETWORK: "dev_default"
echo ""
read -p "Press enter to continue >"

if [ $validation == 1 ]; then

    $DOCKER_COMPOSE up -d wokenvalidation

    $DOCKER_COMPOSE run wait_wokenvalidation

    for i in 1 2 3 4 5 ; do
      $DOCKER_COMPOSE logs chronos | grep java.util.concurrent.TimeoutException || break
      echo "Chronos failed to start, restarting..."
      $DOCKER_COMPOSE stop chronos
      $DOCKER_COMPOSE up -d chronos
      $DOCKER_COMPOSE run wait_chronos
    done

fi

if [ $frontend == 1 ]; then
    echo
    echo "Now that's up to you to play with the user interface..."

    $DOCKER_COMPOSE up -d portalbackend

    $DOCKER_COMPOSE run wait_portal_backend

    $DOCKER_COMPOSE up -d frontend
fi
