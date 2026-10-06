FROM maven:3.9.9-amazoncorretto-21-alpine AS maven_build

COPY pom.xml /build/
COPY src /build/src/

WORKDIR /build/

RUN mvn -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=maven_build /build/target/Locking-1.0-SNAPSHOT.jar /app/

ENTRYPOINT ["java", "-jar", "Locking-1.0-SNAPSHOT.jar"]
