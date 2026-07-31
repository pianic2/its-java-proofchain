#!/usr/bin/env bash
# ProofChain container health probe.
#
# The runtime image is a plain JRE base with no curl, wget or netcat, and none is
# installed: an image build must not depend on a distribution mirror, and every
# extra package is extra attack surface on a read-only, non-root runtime. Bash
# speaks enough HTTP over /dev/tcp to read the readiness probe, which is all this
# needs to do.
#
# Exit 0 only when the readiness group reports UP. Anything else — connection
# refused, timeout, 503, DOWN, OUT_OF_SERVICE — is a failed probe.
set -o errexit
set -o nounset
set -o pipefail

url="${PROOFCHAIN_HEALTH_URL:-http://127.0.0.1:8080/actuator/health/readiness}"
timeout_seconds="${PROOFCHAIN_HEALTH_TIMEOUT_SECONDS:-10}"

hostport="${url#http://}"
path="/${hostport#*/}"
hostport="${hostport%%/*}"
host="${hostport%%:*}"
port="${hostport##*:}"

response=""
if ! exec 3<>"/dev/tcp/${host}/${port}"; then
  exit 1
fi

printf 'GET %s HTTP/1.1\r\nHost: %s\r\nAccept: application/json\r\nConnection: close\r\n\r\n' \
  "${path}" "${hostport}" >&3

# `read` bounds the wait, so a hung connector fails the probe instead of hanging it.
while IFS= read -r -t "${timeout_seconds}" -u 3 line || [ -n "${line:-}" ]; do
  response="${response}${line}"
done

exec 3<&-
exec 3>&-

case "${response}" in
  *'"status":"UP"'*) exit 0 ;;
  *) exit 1 ;;
esac
