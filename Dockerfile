# ---------- 构建阶段 ----------
FROM maven:3.8.6-eclipse-temurin-11 AS build
WORKDIR /build
# 先只拷 pom，利用 Docker 层缓存避免每次改代码都重新拉依赖
COPY pom.xml .
COPY hm-common/pom.xml hm-common/pom.xml
COPY hm-service/pom.xml hm-service/pom.xml
RUN mvn -B -q -DskipTests dependency:go-offline || true
# 再拷源码打包
COPY hm-common hm-common
COPY hm-service hm-service
RUN mvn -B -DskipTests package

# ---------- 运行阶段 ----------
FROM eclipse-temurin:11-jre
WORKDIR /app
ENV TZ=Asia/Shanghai
COPY --from=build /build/hm-service/target/hm-service.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar", "--spring.profiles.active=docker"]
