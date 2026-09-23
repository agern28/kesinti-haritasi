# syntax=docker/dockerfile:1
# Cok asamali: Maven ile build, Spring Boot katmanlarina ayirma, JRE alpine uzerinde root olmayan kullanici.
FROM maven:3-eclipse-temurin-26 AS build
WORKDIR /src
COPY pom.xml .
# Maven deposu BuildKit cache'inde: pom degismedikce bagimliliklar yeniden inmiyor.
RUN --mount=type=cache,target=/root/.m2 mvn -q -B dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -q -B package -DskipTests \
 && java -Djarmode=tools -jar target/*.jar extract --layers --launcher --destination /extracted

FROM eclipse-temurin:21-jre-alpine
ARG APP_VERSION=dev
LABEL org.opencontainers.image.source="https://github.com/agern28/kesinti-haritasi" \
      org.opencontainers.image.title="kesinti-haritasi-api" \
      org.opencontainers.image.description="Kesinti stream'ini tuketir, PostgreSQL'e yazar, REST ve SSE sunar" \
      org.opencontainers.image.version="${APP_VERSION}"
# Sayisal UID: Kubernetes runAsNonRoot kullanici adiyla kontrol edemiyor.
RUN addgroup -S -g 10001 app && adduser -S -u 10001 -G app app
WORKDIR /app
# Katmanlar degisme sikligina gore: bagimliliklar altta, uygulama kodu ustte.
# Kod degisince sadece son katman yeniden cekiliyor.
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
USER 10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
