FROM eclipse-temurin:17-jdk
WORKDIR /app
COPY . .
RUN chmod +x gradlew
RUN ./gradlew build
EXPOSE 8080
CMD ["java", "-jar", "build/libs/*.jar"]