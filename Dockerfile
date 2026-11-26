# syntax=docker/dockerfile:1

FROM maven:3.9-eclipse-temurin-21 AS build
# Serviço autocontido: o contexto do build é a raiz deste repositório (não depende de bibliotecas externas).
WORKDIR /build
COPY pom.xml pom.xml
RUN mvn -q -B dependency:go-offline
COPY src src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine AS runner
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /build/target/audit-service-*.jar app.jar
USER app

# Logs em JSON no stdout (um objeto por linha): é o que o Alloy lê e envia ao Loki. No terminal do host fica texto legível.
ENV LOGGING_STRUCTURED_FORMAT_CONSOLE=logstash

EXPOSE 8090
HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --retries=5 \
  CMD wget -qO- http://localhost:8090/actuator/health >/dev/null || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
