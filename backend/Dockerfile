ARG JAVA_VERSION=21
ARG SPRING_BOOT_VERSION=3.4.5
ARG POSTGRES_IMAGE=postgres:16-alpine
ARG RUNTIME_BASE_IMAGE=eclipse-temurin:21-jre-alpine

FROM ${RUNTIME_BASE_IMAGE}

ARG JAVA_VERSION
ARG SPRING_BOOT_VERSION
ARG POSTGRES_IMAGE
ARG RUNTIME_BASE_IMAGE

LABEL com.diagrammer.baseline.java="${JAVA_VERSION}" \
      com.diagrammer.baseline.spring-boot="${SPRING_BOOT_VERSION}" \
      com.diagrammer.baseline.postgres="${POSTGRES_IMAGE}" \
      com.diagrammer.baseline.runtime-base="${RUNTIME_BASE_IMAGE}"

WORKDIR /app
COPY target/*.jar /app/app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
