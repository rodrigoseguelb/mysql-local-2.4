# ─────────────────────────────────────────────
# ETAPA 1: Build (compilar la aplicación)
# ─────────────────────────────────────────────
FROM eclipse-temurin:17-jdk AS build

# Instala Maven
RUN apt-get update && apt-get install -y maven && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copia el descriptor de dependencias primero (aprovecha el caché de Docker)
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Copia el código fuente y compila
COPY src ./src
RUN mvn clean package -DskipTests -q

# ─────────────────────────────────────────────
# ETAPA 2: Runtime (imagen final, más liviana)
# ─────────────────────────────────────────────
FROM eclipse-temurin:17-jre

WORKDIR /app

# Solo copia el .jar generado en la etapa anterior
COPY --from=build /app/target/bdget-0.0.1-SNAPSHOT.jar app.jar

# Puerto que expone la aplicación
EXPOSE 8080

# Comando de arranque
ENTRYPOINT ["java", "-jar", "app.jar"]
