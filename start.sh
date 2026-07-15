#!/usr/bin/env sh
set -eu

project_root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
JAVA_HOME=$($project_root/scripts/bootstrap-jdk.sh)
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
export MAVEN_USER_HOME="$project_root/.m2"
exec "$project_root/mvnw" -Dmaven.repo.local=.m2/repository spring-boot:run
