#!/bin/sh
# First installation only. Run after reviewing the uploaded release.
set -eu
[ "$(id -u)" -eq 0 ] || { echo 'Run this installer with sudo.' >&2; exit 1; }
release_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
target_dir=/opt/tennis-split-bot
if [ -e "$target_dir" ] || [ -L "$target_dir" ]; then
    echo 'Installation already exists; left unchanged.' >&2
    exit 1
fi
[ -s "$release_dir/bot.env" ] || { echo 'Copy the bot .env to release/bot.env first.' >&2; exit 1; }
docker --context default info >/dev/null
docker --context default compose version
# Fail before installing if the server cannot reach Telegram.
curl -4 -fsS --connect-timeout 10 --max-time 20 -o /dev/null https://api.telegram.org
umask 077
mkdir -m 700 "$target_dir"
cp -R "$release_dir/app" "$target_dir/app"
chown -R root:root "$target_dir/app"
chmod -R a+rX "$target_dir/app"
for file in Dockerfile .dockerignore compose.yaml; do
    install -m 600 "$release_dir/$file" "$target_dir/$file"
done
install -o 10001 -g 10001 -m 600 "$release_dir/bot.env" "$target_dir/bot.env"
install -d -o 10001 -g 10001 -m 700 "$target_dir/data"
cd "$target_dir"
docker --context default compose config -q
docker --context default compose build bot
docker --context default compose up -d bot
docker --context default compose ps
echo 'Check the startup log: sudo docker --context default compose -f /opt/tennis-split-bot/compose.yaml logs --tail 30 bot'
