FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -q -DskipTests dependency:go-offline
COPY src src
COPY data/knowledge data/knowledge

# The parser tests use the fixed demo knowledge pack. Running them while the
# image is built prevents a broken Excel/PDF importer from reaching EC2.
RUN ./mvnw -q test package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/target/*.jar app.jar
COPY data/knowledge /app/data/knowledge
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
