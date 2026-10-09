# ---------- Stage 1: build ----------
# Full JDK + Maven, only used to compile. Thrown away afterwards.
FROM maven:3.9.8-eclipse-temurin-17 AS build
WORKDIR /build

# pom.xml alone first: this layer (all dependencies) stays cached
# until pom.xml changes, so editing Java code does not re-download Maven.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

# Now the source. Tests run in CI, not during the image build.
COPY src ./src
RUN mvn -B -q package -DskipTests


# ---------- Stage 2: runtime ----------
# JRE only - no Maven, no compiler, no source code. Much smaller image.
FROM eclipse-temurin:17.0.11_9-jre-jammy
WORKDIR /app

# Never run as root inside the container.
RUN useradd --system --create-home --uid 10001 app

COPY --from=build --chown=app:app /build/target/*.jar app.jar

USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]