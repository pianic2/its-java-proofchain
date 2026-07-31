#!/usr/bin/env bash
# ProofChain destructive demo reset.
#
# THIS SCRIPT DESTROYS DATA. It prints the exact scope first and refuses to proceed without an
# explicit confirmation phrase.
#
# What it may remove, and nothing else:
#   * the containers and the network of THIS Docker Compose project;
#   * the named volume <project>_proofchain-postgres-data;
#   * the named volume <project>_proofchain-evidence-data.
#
# Each volume is verified, before deletion, to carry this project's Compose labels. The script never
# removes a host directory, never runs `docker system prune`, never runs `rm -rf` against any path,
# and never deletes a volume it has not verified as belonging to this project.
#
# Usage:
#   ./scripts/demo/demo-reset.sh                              # prompts for the phrase
#   echo 'DESTROY PROOFCHAIN DEMO DATA' | ./scripts/demo/demo-reset.sh
#
# Exit codes: 0 reset complete, 1 precondition failed, 3 confirmation refused or not given.

set -euo pipefail
IFS=$'\n\t'

readonly SCRIPT_NAME="${0##*/}"
readonly REPOSITORY_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly CONFIRMATION_PHRASE="DESTROY PROOFCHAIN DEMO DATA"

# The two volume names declared in compose.yml. This list is exhaustive on purpose: the script
# resolves nothing dynamically and can therefore never widen its own scope.
readonly COMPOSE_VOLUMES=("proofchain-postgres-data" "proofchain-evidence-data")

info() { printf '[%s] %s\n' "${SCRIPT_NAME}" "$*"; }
fail() { printf '[%s] ERROR: %s\n' "${SCRIPT_NAME}" "$*" >&2; exit 1; }

cd "${REPOSITORY_ROOT}"

command -v docker >/dev/null 2>&1 || fail "required command not found: docker"
docker compose version >/dev/null 2>&1 || fail "docker compose v2 is not available"
docker info >/dev/null 2>&1 || fail "the Docker daemon is not reachable"
[[ -f "${REPOSITORY_ROOT}/compose.yml" ]] || fail "compose.yml not found at ${REPOSITORY_ROOT}"

# Compose derives the project name from COMPOSE_PROJECT_NAME or from the directory name.
project_name="${COMPOSE_PROJECT_NAME:-$(basename "${REPOSITORY_ROOT}")}"
project_name="$(printf '%s' "${project_name}" | tr '[:upper:]' '[:lower:]' | tr -c 'a-z0-9_-' '-')"
[[ -n "${project_name}" ]] || fail "could not determine the Compose project name"

# --------------------------------------------------------------------------
# Resolve the exact scope. A volume is in scope only when it exists AND its
# Compose labels name this project and one of the two declared volumes.
# --------------------------------------------------------------------------
declare -a in_scope_volumes=()
declare -a foreign_volumes=()
declare -a absent_volumes=()

for volume_key in "${COMPOSE_VOLUMES[@]}"; do
    volume_name="${project_name}_${volume_key}"
    if ! docker volume inspect "${volume_name}" >/dev/null 2>&1; then
        absent_volumes+=("${volume_name}")
        continue
    fi
    labelled_project="$(docker volume inspect "${volume_name}" \
        --format '{{index .Labels "com.docker.compose.project"}}' 2>/dev/null || true)"
    labelled_volume="$(docker volume inspect "${volume_name}" \
        --format '{{index .Labels "com.docker.compose.volume"}}' 2>/dev/null || true)"
    if [[ "${labelled_project}" == "${project_name}" && "${labelled_volume}" == "${volume_key}" ]]; then
        in_scope_volumes+=("${volume_name}")
    else
        foreign_volumes+=("${volume_name} (project='${labelled_project:-none}' volume='${labelled_volume:-none}')")
    fi
done

mapfile -t project_containers < <(docker compose ps --all --format '{{.Name}}' 2>/dev/null || true)

# --------------------------------------------------------------------------
# Print the scope before asking for anything.
# --------------------------------------------------------------------------
cat <<EOF

============================================================================
  DESTRUCTIVE DEMO RESET — ${project_name}
============================================================================

This will PERMANENTLY DELETE the following, and nothing else:

  Containers and network of the Compose project '${project_name}':
EOF

if [[ "${#project_containers[@]}" -eq 0 ]]; then
    printf '    (none currently exist)\n'
else
    printf '    - %s\n' "${project_containers[@]}"
fi

printf '\n  Named Docker volumes, each verified to carry this project'"'"'s Compose labels:\n'
if [[ "${#in_scope_volumes[@]}" -eq 0 ]]; then
    printf '    (none currently exist)\n'
else
    printf '    - %s\n' "${in_scope_volumes[@]}"
fi

if [[ "${#absent_volumes[@]}" -gt 0 ]]; then
    printf '\n  Already absent, nothing to do:\n'
    printf '    - %s\n' "${absent_volumes[@]}"
fi

if [[ "${#foreign_volumes[@]}" -gt 0 ]]; then
    printf '\n  NOT IN SCOPE — these names exist but do not carry this project'"'"'s labels\n'
    printf '  and will NOT be touched:\n'
    printf '    - %s\n' "${foreign_volumes[@]}"
fi

cat <<EOF

  Effect: every registered evidence file, every custody event, every operator,
  case and membership in this local stack is destroyed. There is no undo.

  NOT touched: any host directory, any file in the repository, any image, any
  other Docker volume, container or network. No 'docker system prune' is run.

============================================================================
EOF

if [[ "${#project_containers[@]}" -eq 0 && "${#in_scope_volumes[@]}" -eq 0 ]]; then
    info "nothing to remove; the environment is already clean"
    exit 0
fi

# --------------------------------------------------------------------------
# Explicit confirmation. Never assumed, never defaulted, never bypassable by a
# flag: the phrase must arrive on stdin, typed by a human or piped by one.
# --------------------------------------------------------------------------
if [[ -t 0 ]]; then
    printf '\nType exactly "%s" to proceed: ' "${CONFIRMATION_PHRASE}"
fi

confirmation=""
IFS= read -r confirmation || true

if [[ "${confirmation}" != "${CONFIRMATION_PHRASE}" ]]; then
    printf '[%s] confirmation phrase not given; nothing was deleted.\n' "${SCRIPT_NAME}" >&2
    exit 3
fi

# --------------------------------------------------------------------------
# Delete. `docker compose down --volumes` removes exactly the containers, the
# network and the named volumes declared in compose.yml for this project.
# --------------------------------------------------------------------------
info "confirmed; removing this project's containers, network and named volumes"
docker compose down --volumes --remove-orphans

# Belt and braces: if a declared volume survived (for example because it was detached from the
# project), remove it by name -- but only after re-verifying the labels resolved above.
for volume_name in "${in_scope_volumes[@]:-}"; do
    [[ -n "${volume_name}" ]] || continue
    if docker volume inspect "${volume_name}" >/dev/null 2>&1; then
        info "volume ${volume_name} survived 'compose down --volumes'; removing it by verified name"
        docker volume rm "${volume_name}" >/dev/null
    fi
done

remaining=0
for volume_key in "${COMPOSE_VOLUMES[@]}"; do
    volume_name="${project_name}_${volume_key}"
    if docker volume inspect "${volume_name}" >/dev/null 2>&1; then
        printf '[%s] ERROR: %s still exists after the reset\n' "${SCRIPT_NAME}" "${volume_name}" >&2
        remaining=1
    fi
done
[[ "${remaining}" -eq 0 ]] || fail "the reset did not complete; inspect the volumes listed above by hand"

info "reset complete. The next ./scripts/demo/demo-preflight.sh starts from empty volumes."
