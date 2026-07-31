#!/usr/bin/env bash
# ProofChain demo preflight.
#
# Safe by construction: it validates, starts and waits. It never deletes a volume, never removes a
# host directory, never edits a tracked file and never performs any tampering step. The tampering
# checkpoints in docs/Demo-Guide.md Part B are human-only and are deliberately absent from every
# script in this directory.
#
# Usage:  ./scripts/demo/demo-preflight.sh
#
# Exit codes: 0 ready, 1 precondition failed, 2 the stack did not become ready in time.

set -euo pipefail
IFS=$'\n\t'

readonly SCRIPT_NAME="${0##*/}"
readonly REPOSITORY_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly WORK_DIRECTORY="${REPOSITORY_ROOT}/target/demo"
readonly ENVIRONMENT_FILE="${REPOSITORY_ROOT}/.env"

# Pinned digests of the two synthetic fixtures. A demo that does not start from exactly these bytes
# is not the documented demo, so the mismatch stops here rather than in front of an audience.
readonly EVIDENCE_FIXTURE_SHA256="e3e5108483d028cc9409f5237ddf7db67055a851cfa990cf030711a307aea562"
readonly EVIDENCE_FIXTURE_BYTES=348
readonly OVERSIZED_FIXTURE_SHA256="4225c7625f0ec257408588ee31be9229ce767ec2c77222568e025053f919a99c"
readonly OVERSIZED_FIXTURE_BYTES=1258291

readonly READINESS_ATTEMPTS=60
readonly READINESS_INTERVAL_SECONDS=5

info()  { printf '[%s] %s\n' "${SCRIPT_NAME}" "$*"; }
warn()  { printf '[%s] WARNING: %s\n' "${SCRIPT_NAME}" "$*" >&2; }
fail()  { printf '[%s] ERROR: %s\n' "${SCRIPT_NAME}" "$*" >&2; exit 1; }

require_command() {
    command -v "$1" >/dev/null 2>&1 || fail "required command not found: $1 ($2)"
}

# Reads one key from .env without sourcing it, so a malformed line cannot execute anything and no
# value is ever echoed.
environment_value() {
    local key="$1" line
    line="$(grep -E "^[[:space:]]*${key}=" "${ENVIRONMENT_FILE}" | tail -n 1 || true)"
    [[ -n "${line}" ]] || { printf ''; return 0; }
    line="${line#*=}"
    line="${line%\"}"; line="${line#\"}"
    line="${line%\'}"; line="${line#\'}"
    printf '%s' "${line}"
}

require_configured() {
    local key="$1" value
    value="$(environment_value "${key}")"
    [[ -n "${value}" ]] || fail ".env is missing a value for ${key}"
    case "${value}" in
        '<'*'>'|'changeme'|'CHANGEME')
            fail ".env still holds the placeholder for ${key}; replace it with a local value"
            ;;
    esac
}

port_in_use() {
    local port="$1"
    if command -v ss >/dev/null 2>&1; then
        ss -ltn 2>/dev/null | awk '{print $4}' | grep -Eq "[:.]${port}\$"
    elif command -v lsof >/dev/null 2>&1; then
        lsof -iTCP:"${port}" -sTCP:LISTEN -n -P >/dev/null 2>&1
    else
        return 1
    fi
}

verify_fixture() {
    local path="$1" expected_sha="$2" expected_bytes="$3" actual_sha actual_bytes
    actual_bytes="$(wc -c <"${path}" | tr -d '[:space:]')"
    actual_sha="$(sha256sum "${path}" | cut -d' ' -f1)"
    [[ "${actual_bytes}" == "${expected_bytes}" ]] \
        || fail "fixture ${path##*/} is ${actual_bytes} bytes, expected ${expected_bytes}"
    [[ "${actual_sha}" == "${expected_sha}" ]] \
        || fail "fixture ${path##*/} has digest ${actual_sha}, expected ${expected_sha}"
    info "fixture ${path##*/}: ${actual_bytes} bytes, sha256 ${actual_sha}"
}

cd "${REPOSITORY_ROOT}"

info "repository root: ${REPOSITORY_ROOT}"

# ---------------------------------------------------------------------------
# 1. Tooling
# ---------------------------------------------------------------------------
require_command docker "the demo stack runs on Docker Compose v2"
require_command curl "every demo request is a curl call"
require_command sha256sum "the byte-parity proof needs a SHA-256 tool"
require_command cmp "the byte-parity proof compares the uploaded and downloaded files"
docker compose version >/dev/null 2>&1 || fail "docker compose v2 is not available"
docker info >/dev/null 2>&1 || fail "the Docker daemon is not reachable"
command -v jq >/dev/null 2>&1 || warn "jq is not installed; the guide's responses will not be pretty-printed"
info "tooling: ok"

# ---------------------------------------------------------------------------
# 2. Environment file. Values are checked for presence only and never printed.
# ---------------------------------------------------------------------------
[[ -f "${ENVIRONMENT_FILE}" ]] || fail ".env not found. Copy .env.example to .env and fill it in (see docs/Demo-Guide.md)"

if git -C "${REPOSITORY_ROOT}" ls-files --error-unmatch .env >/dev/null 2>&1; then
    fail ".env is tracked by git. It must stay untracked and must never contain a committed secret"
fi

for key in POSTGRES_PASSWORD DB_PASSWORD PROOFCHAIN_JWT_SECRET \
           PROOFCHAIN_BOOTSTRAP_ADMIN_USERNAME PROOFCHAIN_BOOTSTRAP_ADMIN_EMAIL \
           PROOFCHAIN_BOOTSTRAP_ADMIN_PASSWORD; do
    require_configured "${key}"
done

bootstrap_enabled="$(environment_value PROOFCHAIN_BOOTSTRAP_ADMIN_ENABLED)"
[[ "${bootstrap_enabled}" == "true" ]] \
    || fail "PROOFCHAIN_BOOTSTRAP_ADMIN_ENABLED must be true for the demo; it is the only identity the API cannot create for itself"

active_profile="$(environment_value SPRING_PROFILES_ACTIVE)"
[[ "${active_profile}" == "container" ]] \
    || warn "SPRING_PROFILES_ACTIVE is '${active_profile:-unset}'; the demo is documented against 'container'"

max_file_size="$(environment_value PROOFCHAIN_MAX_FILE_SIZE)"
[[ "${max_file_size}" == "1MB" ]] \
    || warn "PROOFCHAIN_MAX_FILE_SIZE is '${max_file_size:-unset}'; the guide expects 1MB so the 413 step stays fast"

info ".env: present, untracked, complete"

# ---------------------------------------------------------------------------
# 3. Compose definition and published ports
# ---------------------------------------------------------------------------
docker compose config --quiet || fail "compose.yml did not validate against the current .env"

app_port="$(environment_value APP_PORT)"; app_port="${app_port:-8080}"
database_port="$(environment_value POSTGRES_PORT)"; database_port="${database_port:-5432}"

running_containers="$(docker compose ps --quiet 2>/dev/null || true)"
if [[ -z "${running_containers}" ]]; then
    for port in "${app_port}" "${database_port}"; do
        if port_in_use "${port}"; then
            fail "port ${port} is already in use and the stack is not running. Free it, or change APP_PORT / POSTGRES_PORT in .env"
        fi
    done
    info "ports ${app_port} and ${database_port}: free"
else
    info "a Compose container already exists for this project; skipping the port check"
fi

# ---------------------------------------------------------------------------
# 4. Image and stack
# ---------------------------------------------------------------------------
if ! docker image inspect proofchain:1.0.0 >/dev/null 2>&1; then
    info "image proofchain:1.0.0 not present; building it (this can take several minutes)"
    docker compose build
else
    info "image proofchain:1.0.0: present"
fi

info "starting the stack"
docker compose up -d

# ---------------------------------------------------------------------------
# 5. Readiness
# ---------------------------------------------------------------------------
readonly BASE_URL="http://localhost:${app_port}"
info "waiting for ${BASE_URL}/actuator/health/readiness"

ready=0
for attempt in $(seq 1 "${READINESS_ATTEMPTS}"); do
    status="$(curl -s -o /dev/null -w '%{http_code}' "${BASE_URL}/actuator/health/readiness" 2>/dev/null || true)"
    if [[ "${status}" == "200" ]]; then
        info "readiness: UP after ${attempt} attempt(s)"
        ready=1
        break
    fi
    sleep "${READINESS_INTERVAL_SECONDS}"
done

if [[ "${ready}" -ne 1 ]]; then
    warn "the stack did not become ready. Last 40 log lines:"
    docker compose logs --tail 40 proofchain >&2 || true
    printf '[%s] ERROR: readiness did not turn UP within %s seconds\n' \
        "${SCRIPT_NAME}" "$((READINESS_ATTEMPTS * READINESS_INTERVAL_SECONDS))" >&2
    exit 2
fi

curl -s "${BASE_URL}/actuator/health" | tr -d '\n'; printf '\n'

# ---------------------------------------------------------------------------
# 6. Deterministic synthetic fixtures
#
# Written under target/, which is git-ignored, so no fixture can ever be committed. Both files are
# synthetic: a fixed banner, NUL padding and control bytes. Neither contains personal data or any
# real evidence material.
# ---------------------------------------------------------------------------
mkdir -p "${WORK_DIRECTORY}"

{
    printf 'ProofChain demo fixture v1 -- synthetic content, not real evidence.\n'
    head -c 256 /dev/zero
    printf 'CTRL:'
    printf '\001\002\003\004\005\006\007\010\011\013\014\016\017\020'
    printf '\nEND\n'
} > "${WORK_DIRECTORY}/demo-evidence.bin"

head -c "${OVERSIZED_FIXTURE_BYTES}" /dev/zero | tr '\0' 'Z' > "${WORK_DIRECTORY}/demo-oversized-evidence.bin"

verify_fixture "${WORK_DIRECTORY}/demo-evidence.bin" "${EVIDENCE_FIXTURE_SHA256}" "${EVIDENCE_FIXTURE_BYTES}"
verify_fixture "${WORK_DIRECTORY}/demo-oversized-evidence.bin" "${OVERSIZED_FIXTURE_SHA256}" "${OVERSIZED_FIXTURE_BYTES}"

# ---------------------------------------------------------------------------
# 7. Ready
# ---------------------------------------------------------------------------
cat <<EOF

[${SCRIPT_NAME}] preflight complete.

  base URL        ${BASE_URL}
  Swagger UI      ${BASE_URL}/swagger-ui/index.html
  OpenAPI         ${BASE_URL}/v3/api-docs
  work directory  ${WORK_DIRECTORY}

Next, in your presenter shell:

  export BASE=${BASE_URL}
  export WORK=target/demo
  export ADMIN_USERNAME=$(environment_value PROOFCHAIN_BOOTSTRAP_ADMIN_USERNAME)
  export DEMO_OPERATOR_PASSWORD='<a local demo password of at least 12 characters>'
  read -rs -p 'Bootstrap admin password: ' ADMIN_PASSWORD; export ADMIN_PASSWORD; echo

Then follow docs/Demo-Guide.md from step 1.
EOF
