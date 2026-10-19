# 1단계: 빌드. JDK와 Gradle로 실행 가능한 jar를 만든다.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
# 의존성 정의만 먼저 복사해 내려받는다. 소스만 바뀌면 이 층은 캐시를 그대로 쓴다.
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN ./gradlew dependencies --no-daemon -q > /dev/null
COPY src src
RUN ./gradlew bootJar --no-daemon -q -x test
# jar를 의존성·스프링 로더·내 코드 층으로 풀어 둔다. 실행 이미지에서 자주 바뀌는 층만 다시 받게 된다.
RUN java -Djarmode=tools -jar build/libs/coupon-0.0.1-SNAPSHOT.jar extract --layers --launcher --destination extracted

# 2단계: 실행. JDK 대신 JRE만 담은 작은 이미지에 1단계의 결과만 복사한다.
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
USER app
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
# 컨테이너 메모리 제한의 75%까지 힙으로 쓴다.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
