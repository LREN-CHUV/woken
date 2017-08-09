#!/bin/bash -e

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

ROOT_DIR="$(get_script_dir)"

used_ports=$(sudo /bin/sh -c "lsof -iTCP -sTCP:LISTEN -P -n | grep -E ':(8087|8088)'" || true)

if [ "$used_ports" != "" ]; then
  echo "Some applications already use the ports required by this set of applications. Please close them."
  echo -n "$used_ports"
  echo
  exit 1
fi

if pgrep -lf sshuttle > /dev/null ; then
  echo "sshuttle detected. Please close this program as it messes with networking and prevents Docker links to work"
  exit 1
fi

if groups $USER | grep &>/dev/null '\bdocker\b'; then
  DOCKER_COMPOSE="docker-compose"
else
  DOCKER_COMPOSE="sudo docker-compose"
fi

trap '$DOCKER_COMPOSE rm -f' SIGINT SIGQUIT

echo "Remove old running containers (if any)..."
$DOCKER_COMPOSE kill
$DOCKER_COMPOSE rm -f

network_bridge_name="algo-demo-bridge"

if [ $($DOCKER network ls | grep -c $network_bridge_name) -lt 1 ]; then
  echo "Create $network_bridge_name network..."
  $DOCKER network create $network_bridge_name
else
  echo "Found $network_bridge_name network !"
fi

echo "Deploy a Postgres instance and wait for it to be ready..."
$DOCKER_COMPOSE up -d db
$DOCKER_COMPOSE run wait_dbs

echo "Create databases..."
$DOCKER_COMPOSE run create_dbs

echo "Migrate metadata database..."
$DOCKER_COMPOSE run meta_db_setup

echo "Migrate features database..."
$DOCKER_COMPOSE run sample_db_setup

echo "Migrate analytics database..."
$DOCKER_COMPOSE run woken_db_setup

echo "Run containers..."
$DOCKER_COMPOSE up -d zookeeper mesos_master mesos_slave chronos woken woken_validation

$DOCKER_COMPOSE run wait_woken

echo "The Algorithm Factory is now running on your system"

echo "Testing deployment..."

/bin/bash ./test.sh
