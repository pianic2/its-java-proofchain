#!/usr/bin/env bash
# ProofChain demo smoke run.
#
# A thin wrapper around the approved Postman/Newman procedure documented in postman/README.md. It
# runs the delivered collection, in its delivered order, against the running Compose stack.
#
# It is read-and-write against the API only. It performs NO tampering: it never edits the database,
# never touches the evidence volume and never removes anything. The invalid-integrity and
# invalid-chain scenarios are human checkpoints in docs/Demo-Guide.md Part B by design, and no flag
# here can trigger them.
#
# Credentials are never embedded. The bootstrap administrator identity is read at run time from the
# untracked .env. When it differs from the placeholder shipped in the tracked Postman environment, a
# temporary environment file is written with umask 077 into target/demo and deleted on exit, so the
# value never reaches a tracked file and never appears in the process list.
#
# Usage:  ./scripts/demo/demo-smoke.sh [extra newman arguments...]
#
# Exit codes: 0 all assertions passed, 1 precondition failed, otherwise newman's own exit code.

set -euo pipefail
IFS=$'\n\t'

readonly SCRIPT_NAME="${0##*/}"
readonly REPOSITORY_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly WORK_DIRECTORY="${REPOSITORY_ROOT}/target/demo"
readonly ENVIRONMENT_FILE="${REPOSITORY_ROOT}/.env"
readonly COLLECTION="${REPOSITORY_ROOT}/postman/ProofChain.postman_collection.json"
readonly POSTMAN_ENVIRONMENT="${REPOSITORY_ROOT}/postman/ProofChain.local.postman_environment.json"
readonly NEWMAN_VERSION="newman@6.2.2"

info() { printf '[%s] %s\n' "${SCRIPT_NAME}" "$*"; }
fail() { printf '[%s] ERROR: %s\n' "${SCRIPT_NAME}" "$*" >&2; exit 1; }

generated_environment=""
cleanup() {
    [[ -n "${generated_environment}" && -f "${generated_environment}" ]] && rm -f "${generated_environment}"
    return 0
}
trap cleanup EXIT INT TERM

environment_value() {
    local key="$1" line
    line="$(grep -E "^[[:space:]]*${key}=" "${ENVIRONMENT_FILE}" | tail -n 1 || true)"
    [[ -n "${line}" ]] || { printf ''; return 0; }
    line="${line#*=}"
    line="${line%\"}"; line="${line#\"}"
    line="${line%\'}"; line="${line#\'}"
    printf '%s' "${line}"
}

cd "${REPOSITORY_ROOT}"

command -v curl >/dev/null 2>&1 || fail "required command not found: curl"
command -v npx  >/dev/null 2>&1 || fail "npx (Node.js) not found. The smoke run is optional; fall back to docs/Demo-Guide.md Part A, which needs only curl"
[[ -f "${COLLECTION}" ]] || fail "collection not found: ${COLLECTION}"
[[ -f "${POSTMAN_ENVIRONMENT}" ]] || fail "environment not found: ${POSTMAN_ENVIRONMENT}"
[[ -f "${ENVIRONMENT_FILE}" ]] || fail ".env not found. Run ./scripts/demo/demo-preflight.sh first"

app_port="$(environment_value APP_PORT)"; app_port="${app_port:-8080}"
base_url="http://localhost:${app_port}"

readiness="$(curl -s -o /dev/null -w '%{http_code}' "${base_url}/actuator/health/readiness" 2>/dev/null || true)"
[[ "${readiness}" == "200" ]] \
    || fail "readiness at ${base_url} is '${readiness:-unreachable}'. Run ./scripts/demo/demo-preflight.sh first"
info "readiness at ${base_url}: UP"

admin_username="$(environment_value PROOFCHAIN_BOOTSTRAP_ADMIN_USERNAME)"
admin_password="$(environment_value PROOFCHAIN_BOOTSTRAP_ADMIN_PASSWORD)"
[[ -n "${admin_username}" && -n "${admin_password}" ]] \
    || fail "PROOFCHAIN_BOOTSTRAP_ADMIN_USERNAME and PROOFCHAIN_BOOTSTRAP_ADMIN_PASSWORD must be set in .env"

environment_argument="${POSTMAN_ENVIRONMENT}"

tracked_username="$(grep -o '"key"[[:space:]]*:[[:space:]]*"bootstrapAdminUsername"[^}]*' "${POSTMAN_ENVIRONMENT}" | grep -o '"value"[[:space:]]*:[[:space:]]*"[^"]*"' | sed 's/.*"\([^"]*\)"$/\1/' || true)"
tracked_password="$(grep -o '"key"[[:space:]]*:[[:space:]]*"bootstrapAdminPassword"[^}]*' "${POSTMAN_ENVIRONMENT}" | grep -o '"value"[[:space:]]*:[[:space:]]*"[^"]*"' | sed 's/.*"\([^"]*\)"$/\1/' || true)"

if [[ "${admin_username}" != "${tracked_username}" || "${admin_password}" != "${tracked_password}" ]]; then
    info "the local bootstrap identity differs from the tracked placeholder; writing a temporary, umask-077 environment file"
    mkdir -p "${WORK_DIRECTORY}"
    generated_environment="${WORK_DIRECTORY}/newman-environment.local.json"
    (
        umask 077
        USERNAME="${admin_username}" PASSWORD="${admin_password}" \
        node -e '
            const fs = require("fs");
            const file = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
            const replace = { bootstrapAdminUsername: process.env.USERNAME, bootstrapAdminPassword: process.env.PASSWORD };
            for (const value of file.values) {
                if (Object.prototype.hasOwnProperty.call(replace, value.key)) {
                    value.value = replace[value.key];
                }
            }
            file.name = file.name + " (local run)";
            fs.writeFileSync(process.argv[2], JSON.stringify(file));
        ' "${POSTMAN_ENVIRONMENT}" "${generated_environment}"
    )
    [[ -f "${generated_environment}" ]] || fail "could not write the temporary Postman environment"
    environment_argument="${generated_environment}"
    info "temporary environment: ${generated_environment} (removed on exit, and target/ is git-ignored)"
fi

info "running ${NEWMAN_VERSION} against ${base_url}"
set +e
npx --yes "${NEWMAN_VERSION}" run "${COLLECTION}" \
    -e "${environment_argument}" \
    --env-var "baseUrl=${base_url}" \
    "$@"
newman_status=$?
set -e

if [[ "${newman_status}" -eq 0 ]]; then
    info "smoke run passed"
else
    printf '[%s] newman exited with status %s. Reset, run the preflight and re-run the whole collection; do not replay single requests.\n' \
        "${SCRIPT_NAME}" "${newman_status}" >&2
fi
exit "${newman_status}"
