FROM eclipse-temurin:21-jdk AS build
WORKDIR /build
COPY . .
RUN ./mvnw --batch-mode --no-transfer-progress package -DskipTests
RUN mkdir /jars && for module in config-server discovery-server api-gateway user-service course-service task-service submission-service execution-service progress-service; do \
        cp "$module"/target/"$module"-0.0.1-SNAPSHOT.jar "/jars/$module.jar"; \
    done

FROM eclipse-temurin:21-jre
WORKDIR /opt/kodr
COPY --from=build /jars/ /opt/kodr/
USER 10001:10001
ENTRYPOINT ["sh", "-c", "exec java -jar /opt/kodr/${KODR_APP}.jar"]
