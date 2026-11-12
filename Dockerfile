# ---------- 构建阶段 ----------
# 一个 Dockerfile 构建 5 个服务：compose 里通过 build.args.MODULE 指定模块名
FROM maven:3.8.6-eclipse-temurin-11 AS build
ARG MODULE=sm-item
WORKDIR /build
# 先只拷各模块 pom，利用 Docker 层缓存，避免改一行代码就重新拉依赖
COPY pom.xml .
COPY sm-common/pom.xml sm-common/pom.xml
COPY sm-api/pom.xml sm-api/pom.xml
COPY sm-item/pom.xml sm-item/pom.xml
COPY sm-trade/pom.xml sm-trade/pom.xml
COPY sm-pay/pom.xml sm-pay/pom.xml
COPY sm-user/pom.xml sm-user/pom.xml
COPY sm-gateway/pom.xml sm-gateway/pom.xml
RUN mvn -B -q -DskipTests -pl ${MODULE} -am dependency:go-offline || true
# 再拷源码，只构建当前模块及其依赖模块
COPY sm-common sm-common
COPY sm-api sm-api
COPY sm-item sm-item
COPY sm-trade sm-trade
COPY sm-pay sm-pay
COPY sm-user sm-user
COPY sm-gateway sm-gateway
RUN mvn -B -DskipTests -pl ${MODULE} -am package

# ---------- 运行阶段 ----------
FROM eclipse-temurin:11-jre
ARG MODULE=sm-item
WORKDIR /app
ENV TZ=Asia/Shanghai
COPY --from=build /build/${MODULE}/target/${MODULE}.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
