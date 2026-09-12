# ---------- build ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies are copied and resolved first so this layer is cached and only
# re-runs when pom.xml actually changes, not on every source edit.
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

# ---------- run ----------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Never run as root in a container.
RUN addgroup -S meetjava && adduser -S meetjava -G meetjava
USER meetjava

COPY --from=build /build/target/meetjava-1.0.0.jar app.jar

EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
