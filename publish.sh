#!/usr/bin/env bash

#
# Build the release image for the project and publish it on Dockerhub, then
# announce the new version on Slack
#
# Option:
#   --no-tests: skip the test suite

set -o pipefail  # trace ERR through pipes
set -o errtrace  # trace ERR through 'time command' and other functions
set -o errexit   ## set -e : exit the script if any statement returns a non-true return value

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

WORKSPACE=$(get_script_dir)

if pgrep -lf sshuttle > /dev/null ; then
  echo "sshuttle detected. Please close this program as it messes with networking and prevents builds inside Docker to work"
  exit 1
fi

if [ $NO_SUDO ]; then
  DOCKER="docker"
elif groups "$USER" | grep &>/dev/null '\bdocker\b'; then
  DOCKER="docker"
else
  DOCKER="sudo docker"
fi

tests=1
for param in "$@"
do
  if [ "--no-tests" == "$param" ]; then
    tests=0
    echo "INFO: --no-tests option detected !"
  fi
done

# Build
echo "Build the project..."
./build.sh
if [ $tests == 1 ]; then
  ./tests/test.sh
fi
echo "[ok] Done"

count=$(git status --porcelain | wc -l)
if test $count -gt 0; then
  git status
  echo "Not all files have been committed in Git. Release aborted"
  exit 1
fi

select_part() {
  local choice=$1
  case "$choice" in
      "Patch release")
          bumpversion patch
          ;;
      "Minor release")
          bumpversion minor
          ;;
      "Major release")
          bumpversion major
          ;;
      *)
          read -p "Version > " version
          bumpversion --new-version=$version all
          ;;
  esac
}

git pull --tags
# Look for a version tag in Git. If not found, ask the user to provide one
[ $(git tag --points-at HEAD | wc -l) == 1 ] || (
  latest_version=$(bumpversion --dry-run --list patch | grep current_version | sed -r s,"^.*=",, || echo '0.0.1')
  echo
  echo "Current commit has not been tagged with a version. Latest known version is $latest_version."
  echo
  echo 'What do you want to release?'
  PS3='Select the version increment> '
  options=("Patch release" "Minor release" "Major release" "Release with a custom version")
  select choice in "${options[@]}";
  do
    select_part "$choice"
    break
  done
  updated_version=$(bumpversion --dry-run --list patch | grep current_version | sed -r s,"^.*=",,)
  read -p "Release version $updated_version? [y/N] > " ok
  if [ "$ok" != "y" ]; then
    echo "Release aborted"
    exit 1
  fi
)

updated_version=$(bumpversion --dry-run --list patch | grep current_version | sed -r s,"^.*=",,)

# Build again to update the version
echo "Build the project for distribution..."
./build.sh
if [ $tests == 1 ]; then
  ./tests/test.sh
fi
echo "[ok] Done"

# Push on Docker Hub
echo
echo "Publishing..."
IMAGE=hbpmip/woken
$DOCKER push "$IMAGE:latest"
$DOCKER push "$IMAGE:$updated_version"

git push
git push --tags

# Notify on slack
sed "s/USER/${USER^}/" $WORKSPACE/slack.json > $WORKSPACE/.slack.json
sed -i.bak "s/VERSION/$updated_version/" $WORKSPACE/.slack.json
curl -k -X POST --data-urlencode payload@$WORKSPACE/.slack.json https://hbps1.chuv.ch/slack/dev-activity
rm -f $WORKSPACE/.slack.json $WORKSPACE/.slack.json.bak
