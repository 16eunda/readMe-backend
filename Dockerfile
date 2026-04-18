FROM eclipse-temurin:17-jdk
WORKDIR /app
COPY . .
RUN chmod +x gradlew
RUN ./gradlew build -x test
RUN cp build/libs/*.jar app.jar
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
