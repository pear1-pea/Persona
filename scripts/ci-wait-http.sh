#!/usr/bin/env bash
set -u

usage() {
  echo 'Usage: ci-wait-http.sh url pid [attempts=30] [delay=1]' >&2
  exit 2
}

[[ $# -ge 2 && $# -le 4 ]] || usage
url=$1
pid=$2
attempts=${3:-30}
delay=${4:-1}
[[ $url == http://?* || $url == https://?* ]] || usage
[[ $pid =~ ^[1-9][0-9]*$ && ${#pid} -le 9 ]] || usage
[[ $attempts =~ ^[1-9][0-9]*$ && ${#attempts} -le 5 ]] || usage
[[ $delay =~ ^[0-9]+([.][0-9]+)?$ && ${#delay} -le 8 ]] || usage
command -v curl >/dev/null 2>&1 || { echo 'Readiness requires curl.' >&2; exit 2; }

for ((i=1; i<=attempts; i++)); do
  kill -0 "$pid" 2>/dev/null || { echo 'Readiness failed: owned process exited.' >&2; exit 1; }
  if curl --silent --fail --output /dev/null --connect-timeout 1 --max-time 2 "$url" 2>/dev/null; then
    kill -0 "$pid" 2>/dev/null || { echo 'Readiness failed: owned process exited.' >&2; exit 1; }
    echo 'HTTP endpoint ready.'
    exit 0
  fi
  kill -0 "$pid" 2>/dev/null || { echo 'Readiness failed: owned process exited.' >&2; exit 1; }
  if ((i < attempts)); then
    sleep "$delay" || exit 2
  fi
done
echo 'Readiness deadline exhausted.' >&2
exit 1
