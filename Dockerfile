FROM eclipse-temurin:17-jdk-alpine

WORKDIR /app

COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN ./mvnw dependency:go-offline -B

COPY src ./src
RUN ./mvnw package -DskipTests -B

EXPOSE 7000
EXPOSE 6000

CMD ["java", "-cp", "target/classes:target/dependency/*", "RelayServer"]
