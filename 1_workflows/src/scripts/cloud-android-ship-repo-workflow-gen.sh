#!/bin/sh
# cloud-android-ship-repo-workflow-gen — alias to the workflow engine
exec sh "$(dirname "$0")/cloud-android-ship-repo-workflow-engine.sh" "$@"
