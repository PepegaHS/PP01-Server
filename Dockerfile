FROM postgres:17.7-alpine AS postgres-init
COPY --chmod=0555 infra/postgres/01-create-databases.sh /usr/local/bin/kodr-create-databases
ENTRYPOINT ["/usr/local/bin/kodr-create-databases"]

FROM judge0/judge0:1.13.1 AS judge0
COPY --chmod=0444 infra/judge0/judge0.conf /judge0.conf

FROM eclipse-temurin:21-jdk AS build
WORKDIR /build
COPY . .
RUN ./mvnw --batch-mode --no-transfer-progress package -DskipTests
RUN mkdir /jars && for module in config-server discovery-server api-gateway auth-service user-service course-service task-service submission-service execution-service progress-service; do \
        cp "$module"/target/"$module"-0.0.1-SNAPSHOT.jar "/jars/$module.jar"; \
    done

FROM eclipse-temurin:21-jre AS app
WORKDIR /opt/kodr
COPY --from=build /jars/ /opt/kodr/
COPY --from=build /build/config/ /opt/kodr/config/
USER 10001:10001
ENTRYPOINT ["sh", "-c", "exec java -jar /opt/kodr/${KODR_APP}.jar"]
