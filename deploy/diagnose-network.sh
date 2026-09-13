#!/bin/sh
# Read-only host diagnostics for the public Telegram API; no token is used.
set -eu
[ "$(id -u)" -eq 0 ] || { echo 'Запусти через sudo: чтение сетевых правил и захват заголовков требуют прав администратора.' >&2; exit 1; }
umask 077
report_dir=$(mktemp -d /tmp/tennis-network.XXXXXX)
finish() {
    if [ -n "${SUDO_UID:-}" ] && [ -n "${SUDO_GID:-}" ]; then
        chown -R "$SUDO_UID:$SUDO_GID" "$report_dir"
    fi
    printf 'Отчёт: %s\n' "$report_dir"
}
trap finish EXIT
target_host=api.telegram.org
target_ip=$(getent ahostsv4 "$target_host" | awk 'NR==1 {print $1}')
[ -n "$target_ip" ] || { echo 'DNS не вернул IPv4-адрес Telegram.' >&2; exit 1; }
case "$target_ip" in *[!0-9.]* ) echo 'Некорректный IPv4-адрес.' >&2; exit 1 ;; esac
{
    date -u
    printf 'Telegram IPv4: %s\n' "$target_ip"
    ip -4 route get "$target_ip"
    ip -4 rule show
} > "$report_dir/route.txt" 2>&1
target_interface=$(ip -4 route get "$target_ip" | awk '{for(i=1;i<NF;i++) if($i=="dev") {print $(i+1);exit}}')
[ -n "$target_interface" ] || { echo 'Не найден исходящий сетевой интерфейс.' >&2; exit 1; }
if command -v nft >/dev/null 2>&1; then nft -a list ruleset > "$report_dir/nft.txt" 2>&1 || true; fi
if command -v iptables-save >/dev/null 2>&1; then iptables-save -c > "$report_dir/iptables.txt" 2>&1 || true; fi
capture_pid=
if command -v tcpdump >/dev/null 2>&1 && command -v timeout >/dev/null 2>&1; then
    # Header summary only, restricted to this destination and TCP/443.
    timeout 14 tcpdump -nn -l -i "$target_interface" -s 96 "host $target_ip and tcp port 443" > "$report_dir/tcp.txt" 2>&1 &
    capture_pid=$!
    sleep 1
fi
set +e
curl -4 -sS --connect-timeout 8 --max-time 10 --resolve "$target_host:443:$target_ip" \
    -o /dev/null -w 'HTTP=%{http_code} connect=%{time_connect} TLS=%{time_appconnect}\n' \
    "https://$target_host" > "$report_dir/curl.txt" 2>&1
curl_status=$?
set -e
printf 'exit_code=%s\n' "$curl_status" >> "$report_dir/curl.txt"
if [ -n "$capture_pid" ]; then wait "$capture_pid" || true; fi
cat "$report_dir/curl.txt"
echo 'Настройки сети, службы и данные приложений не изменялись. Отчёт содержит сетевые адреса; не публикуй его в GitHub.'
