#!/bin/sh
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
release_dir=${1:?Usage: sh deploy/build.sh /absolute/path/to/new-release}
case "$release_dir" in /*) ;; *) echo 'Use an absolute output path.' >&2; exit 1 ;; esac
if [ -e "$release_dir" ] || [ -L "$release_dir" ]; then
    echo 'Output already exists; choose a new directory.' >&2
    exit 1
fi
sh "$project_dir/scripts/gradle.sh" build installDist --no-daemon
sh "$project_dir/deploy/stage.sh" "$project_dir/build/install/tennis-settlements-bot" "$release_dir"
