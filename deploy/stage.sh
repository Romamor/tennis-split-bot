#!/bin/sh
# Package only runtime files. Dependencies and application occupy separate image layers.
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
dist_dir=${1:?Usage: sh deploy/stage.sh distribution-dir /absolute/new-release}
release_dir=${2:?Usage: sh deploy/stage.sh distribution-dir /absolute/new-release}
case "$release_dir" in /*) ;; *) echo 'Use an absolute output path.' >&2; exit 1 ;; esac
[ ! -e "$release_dir" ] && [ ! -L "$release_dir" ] || { echo 'Output already exists.' >&2; exit 1; }
[ -f "$dist_dir/lib/tennis-settlements-bot.jar" ] && [ -f "$dist_dir/bin/tennis-settlements-bot" ] || { echo 'Build installDist first.' >&2; exit 1; }
mkdir -m 700 "$release_dir"
cp -R "$dist_dir" "$release_dir/app"
mv "$release_dir/app/lib/tennis-settlements-bot.jar" "$release_dir/app/tennis-settlements-bot.jar"
for file in Dockerfile .dockerignore compose.yaml install.sh; do
    cp "$project_dir/deploy/$file" "$release_dir/$file"
done
echo "Release prepared: $release_dir (token and databases are excluded)."
