# ---------------------------------------------------------------------------
# Build stage. Pinned Eclipse Temurin Java 25 JDK, the repository Maven Wrapper
# and Maven Central. Nothing else is reachable and nothing else is required.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jdk AS build

WORKDIR /workspace

# Optional support for a TLS-inspecting egress proxy. The secret is empty unless
# one is supplied, and it is imported into the throwaway build stage only, so the
# runtime image below never contains a certificate, a proxy setting or a secret.
RUN --mount=type=secret,id=build-ca,target=/run/secrets/build-ca.crt \
    if [ -s /run/secrets/build-ca.crt ]; then \
      csplit -sz -f /tmp/egress-ca- -b '%03d.pem' /run/secrets/build-ca.crt '/BEGIN CERTIFICATE/' '{*}'; \
      index=0; \
      for certificate in /tmp/egress-ca-*.pem; do \
        keytool -importcert -noprompt -trustcacerts -alias "egress-proxy-ca-${index}" \
          -file "${certificate}" -cacerts -storepass changeit \
          || echo "skipped already trusted certificate ${certificate}"; \
        index=$((index + 1)); \
      done; \
      rm -f /tmp/egress-ca-*.pem; \
    fi

# See the script header: this is what keeps the Maven Wrapper on its pinned,
# checksum-verified ZIP distribution without adding an OS package repository.
COPY --chmod=0555 docker/unzip-for-maven-wrapper.sh /usr/local/bin/unzip

# The wrapper and the descriptor change far less often than the sources, so the
# dependency resolution layer is cached independently of the application code.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    ./mvnw --batch-mode --no-transfer-progress -DskipTests dependency:go-offline

COPY src/ src/

# Tests are not run here: the suite needs a Docker daemon for Testcontainers,
# which an image build must never require. `./mvnw clean verify` is the gate.
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    ./mvnw --batch-mode --no-transfer-progress -DskipTests clean package \
    && cp target/proofchain-*.jar /workspace/proofchain.jar

# ---------------------------------------------------------------------------
# Runtime stage. Pinned Eclipse Temurin Java 25 JRE. Only the packaged jar and
# the health probe script cross the stage boundary: no sources, no build cache,
# no Maven repository, no wrapper.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jre AS runtime

# Deterministic identity. A stable numeric pair keeps volume ownership stable
# across rebuilds and keeps `docker compose exec ... id` reproducible.
ARG PROOFCHAIN_UID=10001
ARG PROOFCHAIN_GID=10001

LABEL org.opencontainers.image.title="ProofChain" \
      org.opencontainers.image.description="Chain-of-custody backend for digital evidence" \
      org.opencontainers.image.version="1.0.0" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.base.name="docker.io/library/eclipse-temurin:25-jre"

RUN groupadd --system --gid "${PROOFCHAIN_GID}" proofchain \
    && useradd --system --uid "${PROOFCHAIN_UID}" --gid "${PROOFCHAIN_GID}" \
       --home-dir /var/lib/proofchain --shell /usr/sbin/nologin proofchain \
    && mkdir -p /var/lib/proofchain/storage \
    && chown -R "${PROOFCHAIN_UID}:${PROOFCHAIN_GID}" /var/lib/proofchain \
    && install -d -o root -g root -m 0555 /opt/proofchain

# Owned by root and only readable by the runtime user: the application can
# never rewrite its own code or its own health probe.
COPY --from=build --chown=root:root --chmod=0444 /workspace/proofchain.jar /opt/proofchain/proofchain.jar
COPY --chown=root:root --chmod=0555 docker/healthcheck.sh /usr/local/bin/proofchain-health

USER ${PROOFCHAIN_UID}:${PROOFCHAIN_GID}
WORKDIR /var/lib/proofchain

# The canonical container storage root. Compose mounts the evidence volume here.
ENV PROOFCHAIN_STORAGE_ROOT=/var/lib/proofchain/storage \
    SPRING_PROFILES_ACTIVE=container \
    PROOFCHAIN_HEALTH_URL=http://127.0.0.1:8080/actuator/health/readiness

EXPOSE 8080

# `java` is PID 1 and installs its own SIGTERM handler, so `docker compose stop`
# reaches the Spring shutdown hook directly. No shell and no wrapper swallow it.
# The temporary directory is the bounded tmpfs, never the read-only root.
ENTRYPOINT ["java", \
            "-XX:MaxRAMPercentage=75.0", \
            "-Djava.io.tmpdir=/tmp", \
            "-Djava.security.egd=file:/dev/urandom", \
            "-jar", "/opt/proofchain/proofchain.jar"]
