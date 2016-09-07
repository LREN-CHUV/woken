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

used_ports=$(sudo /bin/sh -c "lsof -iTCP -sTCP:LISTEN -P -n | grep -E ':(8087|5432|2181|2888|3888|5050|5051|4400|65432)'" || true)

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

echo "Starting the Mesos environment and the woken application..."

cp $ROOT_DIR/../target/scala-2.11/woken-assembly-githash.jar woken/lib/woken.jar

if groups $USER | grep &>/dev/null '\bdocker\b'; then
  DOCKER_COMPOSE="docker-compose"
else
  DOCKER_COMPOSE="sudo docker-compose"
fi

trap '$DOCKER_COMPOSE rm -f' SIGINT SIGQUIT

export HOST="localhost"
$DOCKER_COMPOSE up
