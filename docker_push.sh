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

export WORKSPACE=$(get_script_dir)
cd $WORKSPACE/docker
captain push --commit-tags woken
curl -k -X POST --data-urlencode payload@$WORKSPACE/docker/slack.json https://hbps1.chuv.ch/slack/dev-activity
