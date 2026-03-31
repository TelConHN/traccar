# =============================================================================
# Dockerfile — Traccar (build desde código fuente)
#
# Multi-stage: no necesitas tener Node ni Java instalados en tu máquina.
# Docker descarga las herramientas, compila todo adentro, y produce
# una imagen mínima lista para correr.
#
# Etapas:
#   1. frontend-builder  → compila traccar-web con Node 22
#   2. backend-builder   → compila el servidor con Java 17 + Gradle
#   3. runtime           → imagen final liviana solo con lo necesario
# =============================================================================

# ── Etapa 1: compilar el frontend (React) ─────────────────────────────────────
FROM node:22-alpine AS frontend-builder

WORKDIR /frontend

# Copiar dependencias primero — Docker cachea esta capa si package.json no cambia
COPY traccar-web/package*.json ./
RUN npm ci --prefer-offline --legacy-peer-deps

# Copiar el código fuente del frontend y compilar
COPY traccar-web/ ./
RUN npm run build
# Resultado: /frontend/build/


# ── Etapa 2: compilar el backend (Java) ───────────────────────────────────────
FROM eclipse-temurin:17-jdk-jammy AS backend-builder

WORKDIR /build

# Copiar el wrapper de Gradle primero — Docker cachea si build.gradle no cambia
COPY gradlew ./
COPY gradle/ ./gradle/
# sed elimina los \r por si gradlew tiene saltos de línea Windows (CRLF)
RUN sed -i 's/\r//' gradlew && chmod +x gradlew

# Descargar dependencias (capa cacheada)
COPY build.gradle settings.gradle ./
RUN ./gradlew dependencies --no-daemon 2>/dev/null || true

# Copiar el código fuente y compilar
COPY src/ ./src/
COPY setup/ ./setup/
# Convertir CRLF → LF en todos los archivos Java (checkout en Windows los deja con \r\n)
RUN find src -name "*.java" -exec sed -i 's/\r//' {} +
RUN ./gradlew build -x test --no-daemon
# Resultado: /build/target/tracker-server.jar


# ── Etapa 3: imagen de runtime (liviana) ──────────────────────────────────────
FROM eclipse-temurin:17-jre-jammy

WORKDIR /opt/traccar

# Solo lo necesario para correr — sin JDK, sin Gradle, sin Node
COPY --from=backend-builder /build/target/tracker-server.jar ./tracker-server.jar
COPY --from=backend-builder /build/target/lib/               ./lib/
COPY --from=frontend-builder /frontend/build/                ./web/

# Configuración mínima — la config real viene de variables de entorno
# (CONFIG_USE_ENVIRONMENT_VARIABLES=true en docker-compose.yml)
RUN mkdir -p conf
COPY traccar.xml ./conf/traccar.xml

# Directorios para logs y datos persistentes
RUN mkdir -p logs data

# Puerto de la API y la interfaz web
EXPOSE 8082
# Puertos de protocolos GPS (los dispositivos se conectan aquí)
EXPOSE 5000-5150

ENTRYPOINT ["java", "-jar", "tracker-server.jar", "conf/traccar.xml"]
# La configuración se inyecta por variables de entorno en docker-compose.yml
# (CONFIG_USE_ENVIRONMENT_VARIABLES=true)
