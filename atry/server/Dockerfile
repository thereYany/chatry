# syntax=docker/dockerfile:1
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY atry-platform/pom.xml atry-platform/
COPY atry-contracts/pom.xml atry-contracts/
COPY atry-identity/pom.xml atry-identity/
COPY atry-payment/pom.xml atry-payment/
COPY atry-llm/pom.xml atry-llm/
COPY atry-metering/pom.xml atry-metering/
COPY atry-app/pom.xml atry-app/
COPY docker/maven-settings.xml /root/.m2/settings.xml
COPY atry-platform/src ./atry-platform/src
COPY atry-contracts/src ./atry-contracts/src
COPY atry-identity/src ./atry-identity/src
COPY atry-payment/src ./atry-payment/src
COPY atry-llm/src ./atry-llm/src
COPY atry-metering/src ./atry-metering/src
COPY atry-app/src ./atry-app/src
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21-jdk-alpine
# 堆 = mem_limit × 50%（compose 默认 768m → MaxHeapSize=384MiB），固定非堆约 221m + 映射约 70m
ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-XX:MaxRAMPercentage=50 -XX:MaxDirectMemorySize=64m -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/app/logs -Duser.timezone=Asia/Shanghai -Xlog:gc*:file=/app/logs/gc.log:time,uptime,level,tags:filecount=5,filesize=20M"
WORKDIR /app
RUN addgroup -S app && adduser -S -G app app && mkdir -p /app/logs && chown -R app:app /app
COPY --chown=app:app --from=build /workspace/atry-app/target/atry-app-*-exec.jar /app/app.jar
USER app
EXPOSE 8084 9091
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
