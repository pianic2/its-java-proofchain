# ProofChain Postman package

Deterministic, secret-free Postman package that exercises the complete approved ProofChain `1.0.0` HTTP surface against a local Docker Compose deployment.

| Artifact | Purpose |
| --- | --- |
| `ProofChain.postman_collection.json` | The collection: 14 ordered modules, 97 requests, 200 assertions. |
| `ProofChain.local.postman_environment.json` | Placeholders and non-sensitive defaults for a local stack. |
| `README.md` | This guide: preconditions, execution, ordering, and known limits. |

The collection calls only endpoints the API already publishes. No endpoint, alias, generic command, bulk operation or demo-only route was added to make it run, and the OpenAPI document it checks is the runtime-generated one — there is no separate specification file.

## What the environment contains

Every value in `ProofChain.local.postman_environment.json` is a placeholder or a non-sensitive default. There is no JWT, no password hash, no database credential, no absolute host path and no evidence data in any tracked file.

| Variable | Default | Meaning |
| --- | --- | --- |
| `baseUrl` | `http://localhost:8080` | Published Compose port. |
| `bootstrapAdminUsername` | `proofchain-admin` | Username you configure for the opt-in bootstrap administrator. |
| `bootstrapAdminPassword` | `local-only-placeholder-password` | **Placeholder, not a credential.** It authenticates nothing until you set the identical string as `PROOFCHAIN_BOOTSTRAP_ADMIN_PASSWORD` in your own untracked `.env`. Replace both with a value of your choice for any stack you care about. |
| `multipartBoundary` | `----ProofChainSyntheticBoundary7f3a` | Boundary of the hand-built multipart body. |
| `oversizedEvidenceBytes` | `1258291` | Size of the synthetic payload used to prove the `413` contract. |
| `unknownOperatorId`, `unknownCaseId`, `unknownEvidenceId`, `unknownEventId` | fictional UUIDs | Identifiers guaranteed never to exist. |

Everything produced at run time — bearer tokens, generated operator/case/evidence/event identifiers, the synthetic actor password, the synthetic evidence bytes and their digest — is written to **collection variables during the run only**. Newman never writes those values back, so no run can leak a token or a password into a tracked file.

## Preconditions

1. A built image and a running stack, following [container operations](../docs/Operations.md).
2. An untracked `.env` at the repository root, created from `.env.example`, with these values:

   ```bash
   POSTGRES_PASSWORD=<your local database password>
   DB_PASSWORD=<the same local database password>
   PROOFCHAIN_JWT_SECRET=<openssl rand -base64 32>
   SPRING_PROFILES_ACTIVE=container

   # The bootstrap administrator is the only identity the collection cannot create for itself.
   PROOFCHAIN_BOOTSTRAP_ADMIN_ENABLED=true
   PROOFCHAIN_BOOTSTRAP_ADMIN_USERNAME=proofchain-admin
   PROOFCHAIN_BOOTSTRAP_ADMIN_EMAIL=proofchain-admin@example.org
   PROOFCHAIN_BOOTSTRAP_ADMIN_PASSWORD=local-only-placeholder-password

   # Keeps the oversized-upload scenario fast. With the 50MB default the same request would have to
   # transfer more than 50MB to prove the identical 413 contract.
   PROOFCHAIN_MAX_FILE_SIZE=1MB
   PROOFCHAIN_MAX_REQUEST_SIZE=2MB
   ```

   `.env` is git-ignored. Keep `bootstrapAdminPassword` in the environment file and `PROOFCHAIN_BOOTSTRAP_ADMIN_PASSWORD` in `.env` identical, and change both together.

3. Start the stack and wait for readiness:

   ```bash
   docker compose up -d --build
   curl -s http://localhost:8080/actuator/health/readiness
   ```

No manual database edit, no SQL, no pre-existing user-specific environment and no tracked fixture file is required. Every actor beyond the bootstrap administrator is created by the collection through `POST /api/v1/operators`.

## Running it

### Postman GUI

1. **Import** → *Files* → select both `ProofChain.postman_collection.json` and `ProofChain.local.postman_environment.json`.
2. Select the **ProofChain local** environment in the environment selector.
3. Open the collection → **Run** (collection runner).
4. Leave *Keep variable values* **off** and the folder order **unchanged**, then *Run ProofChain API*.
5. Individual requests can be replayed inside a module, but a module only makes sense after the modules before it have run in the same session, because it consumes the identifiers they captured.

### Newman

Pinned, from the repository root:

```bash
npx --yes newman@6.2.2 run postman/ProofChain.postman_collection.json \
  -e postman/ProofChain.local.postman_environment.json
```

Node is required **only** for this optional command. The Maven build has no Node dependency, `pom.xml` is untouched by this package, and no GitHub Actions workflow runs the collection.

### Repeatability

The collection derives a `runId` from the wall clock at the first request and suffixes every operator username, e-mail and evidence reference tag with it, so consecutive runs against the same stack do not collide. A destructive reset is therefore optional:

```bash
docker compose down -v --remove-orphans
docker compose up -d
```

After the reset the volumes are empty, Flyway recreates the schema and the bootstrap administrator is recreated, and the identical Newman command produces the identical counts.

## Module order and what each module proves

The runner executes modules top to bottom; each depends on identifiers captured by the previous ones.

| # | Module | Proves |
| --- | --- | --- |
| 00 | Health and documentation | The three sanitized probes answer unauthenticated and render nothing but a status; the generated contract declares the global bearer scheme and exposes exactly one unauthenticated operation. |
| 01 | Authentication | Bootstrap login, `401` on wrong credentials, `400` on a blank username, current identity, `401` without a token. Captures the ADMIN token. |
| 02 | Operator administration | Creates the five synthetic actors through the approved API, duplicate identity `409`, paging and its `400`, role and status changes, suspended login refused indistinguishably, `403` for a non-ADMIN. Captures one token per actor. |
| 03 | Custody cases | Case creation with `Location`, `403` for a role that may not create cases, metadata patch, empty and unknown-property `400`, and a hidden `404` proven byte-identical to a nonexistent `404`. |
| 04 | Memberships | Idempotent assignment (`201` then `200`), no ADMIN membership, no inactive member, no last-responsible-manager removal, idempotent `204` removal, `404` for a non-member reader. |
| 05 | Evidence registration | Multipart registration from synthetic bytes with server-side digest and size parity, duplicate reference tag `409`, oversized `413`, missing part `400`, `403` for a visible member without the role, `404` for a non-member. |
| 06 | Evidence read and download | Page and detail shape without internals, `404` for missing and hidden evidence, and byte-for-byte download parity. |
| 07 | Timeline and chain verification | The genesis event, one event detail, `404` for an unknown event, and an intact chain. |
| 08 | Integrity verification | Digest and size recomputed over the stored bytes, and the appended `INTEGRITY_VERIFIED` event. |
| 09 | Custody transfer | Ineligible holder `409`, successful transfer with its chained event, no-op `409`, role `403`, blank reason `400`. |
| 10 | Metadata update | Descriptive correction with content identity untouched, no-op `409`, unknown property `400`. |
| 11 | Seal and release | Role `403`, seal, repeated seal `409`, release, repeated release `409`. |
| 12 | Case closure and immutability | Irreversible closure, idempotent repeat, reopen `409`, every mutation `409`, and reads, downloads, the full ordered timeline and chain verification still green. |
| 13 | Negative and security scenarios | Missing, malformed, tampered and non-bearer credentials, hidden vs missing indistinguishability, administration closed to non-administrators, and an invalid identifier as `400`. |

### Binary parity

The evidence body is built in a pre-request script from every ASCII code point except CR and LF, repeated three times — 378 bytes containing `NUL` and the other control bytes. The script computes the SHA-256 and the byte length locally; the collection then asserts that the registration response, the summary, the detail, the integrity verification, the custody event payload and the downloaded body all agree with the locally computed digest and size, and that the downloaded body is character-for-character the uploaded one. Nothing is read from disk, so no binary fixture is tracked and no local path is referenced.

The payload deliberately stays inside US-ASCII: a Postman raw request body is transmitted as UTF-8, so only code points below `0x80` survive a round trip unchanged, and CR/LF are excluded so the multipart framing cannot be misread.

### Invalid integrity result

`POST /api/v1/evidences/{evidenceId}/verify-integrity` reports `valid: false` only when the stored bytes or size stop matching the digest recorded at registration. No approved endpoint can alter stored content, and the collection deliberately performs no filesystem or database edit, so it cannot manufacture that state. Instead it asserts the invariant that produces the verdict — `valid === (expectedContentSha256 === actualContentSha256 && expectedFileSize === actualFileSize)` — so a divergence would necessarily be reported as invalid.

To observe it manually, outside the collection, corrupt one byte in the evidence volume and re-run the request:

```bash
docker compose exec proofchain sh -c 'f=$(find /var/lib/proofchain/storage -type f ! -path "*/.staging/*" | head -1); printf X | dd of=$f bs=1 seek=0 conv=notrunc'
```

The response then reports `valid: false` with diverging `expectedContentSha256` and `actualContentSha256`. Recreate the stack afterwards with `docker compose down -v --remove-orphans`.

## Secret hygiene

`ApiSurfaceContractIT` (in `src/test/java/it/itsprodigi/proofchain/`) parses all three artifacts on every `mvn verify`. It fails the build when the collection deviates from the Postman v2.1 schema shape, when it calls an endpoint outside the approved allowlist, when it stops covering an approved endpoint, or when any artifact contains a JWT-shaped string, a BCrypt hash, a JDBC URL, a database credential or a host filesystem path — and it requires every environment key whose name mentions a password to remain a named placeholder.
