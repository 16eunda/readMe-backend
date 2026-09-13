# --- build stage -------------------------------------------------------------
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
# .dockerignore 가 .env / service-account.json 을 제외한다.
COPY . .
RUN chmod +x gradlew \
 && ./gradlew --no-daemon build -x test \
 && find build/libs -name "*.jar" ! -name "*-plain.jar" -exec cp {} app.jar \;

# --- runtime stage -----------------------------------------------------------
# 최종 이미지에는 jar 만 들어간다. 소스도 빌드 캐시도 시크릿도 남지 않는다.
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/app.jar app.jar
EXPOSE 8080
# 시크릿(DATABASE_*, JWT_SECRET, GEMINI_API_KEY, GOOGLE_SERVICE_ACCOUNT_JSON)은
# 전부 런타임 환경변수로 주입한다.
CMD ["java", "-Xmx256m", "-Xms128m", "-XX:+UseSerialGC", "-jar", "app.jar"]
