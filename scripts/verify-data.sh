#!/usr/bin/env sh
set -eu

project_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
JAVA_HOME=$($project_root/scripts/bootstrap-jdk.sh)
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
export MAVEN_USER_HOME="$project_root/.m2"

cd "$project_root"
./mvnw -Dmaven.repo.local=.m2/repository -Pexternal test

latest=$(find target/data-verification -maxdepth 1 -name 'verification-*.md' -type f | sort | tail -n 1)
if [ -z "$latest" ]; then
  echo "Live data verification completed without producing a report." >&2
  exit 1
fi
echo "Verification report: $project_root/$latest"
