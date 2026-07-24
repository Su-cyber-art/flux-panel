ARG APP_VERSION=dev

FROM maven:3.9-eclipse-temurin-21 AS build

ARG APP_VERSION
WORKDIR /app

COPY pom.xml .
COPY src ./src
RUN mvn --batch-mode clean package -DskipTests -Drevision=${APP_VERSION}

FROM eclipse-temurin:21-jre-jammy

ARG APP_VERSION
LABEL org.opencontainers.image.version=${APP_VERSION}

WORKDIR /app
ENV APP_VERSION=${APP_VERSION}
ENV JAVA_OPTS="-Xmx512m -Xms256m -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"
ENV LANG=en_US.UTF-8
ENV LANGUAGE=en_US:en
ENV LC_ALL=en_US.UTF-8

RUN apt-get update \
    && apt-get install -y --no-install-recommends fontconfig fonts-dejavu wget \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /app/target/*.jar app.jar

EXPOSE 6365

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
