# ---------- 构建阶段 ----------
# 一个 Dockerfile 构建 5 个服务：compose 里通过 build.args.MODULE 指定模块名
FROM maven:3.8.6-eclipse-temurin-11 AS build
ARG MODULE=hm-item
WORKDIR /build
# 先只拷各模块 pom，利用 Docker 层缓存，避免改一行代码就重新拉依赖
COPY pom.xml .
COPY hm-common/pom.xml hm-common/pom.xml
COPY hm-api/pom.xml hm-api/pom.xml
COPY hm-item/pom.xml hm-item/pom.xml
COPY hm-trade/pom.xml hm-trade/pom.xml
COPY hm-pay/pom.xml hm-pay/pom.xml
COPY hm-user/pom.xml hm-user/pom.xml
COPY hm-gateway/pom.xml hm-gateway/pom.xml
RUN mvn -B -q -DskipTests -pl ${MODULE} -am dependency:go-offline || true
# 再拷源码，只构建当前模块及其依赖模块
COPY hm-common hm-common
COPY hm-api hm-api
COPY hm-item hm-item
COPY hm-trade hm-trade
COPY hm-pay hm-pay
COPY hm-user hm-user
COPY hm-gateway hm-gateway
RUN mvn -B -DskipTests -pl ${MODULE} -am package

# ---------- 运行阶段 ----------
FROM eclipse-temurin:11-jre
ARG MODULE=hm-item
WORKDIR /app
ENV TZ=Asia/Shanghai
COPY --from=build /build/${MODULE}/target/${MODULE}.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
