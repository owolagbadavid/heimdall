# ── Build stage ───────────────────────────────────────────────────────
FROM eclipse-temurin:25-jdk AS builder

WORKDIR /build

COPY .mvn/ .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw

# Download dependencies (cached layer)
RUN --mount=type=cache,target=/root/.m2 ./mvnw dependency:go-offline

COPY ./src ./src

# Build the application
RUN --mount=type=cache,target=/root/.m2 rm -rf target && ./mvnw clean package spring-boot:repackage -DskipTests

# ── Runtime stage ─────────────────────────────────────────────────────
FROM eclipse-temurin:25-jdk AS runtime

ARG user=heimdall
ARG group=heimdall
ARG uid=1050
ARG gid=1050
ARG APP_HOME=/opt/heimdall

ENV APP_HOME=${APP_HOME}

RUN groupadd -g ${gid} ${group} \
    && useradd -m -u ${uid} -g ${gid} -s /bin/bash ${user} \
    && mkdir -p $APP_HOME \
    && chown ${uid}:${gid} $APP_HOME

WORKDIR $APP_HOME

USER ${user}

COPY --from=builder /build/target/*.jar ./application.jar

# HTTP port
EXPOSE 8080
# gRPC port
EXPOSE 9090

ENTRYPOINT ["java", "-jar", "application.jar"]