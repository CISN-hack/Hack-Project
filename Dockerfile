# syntax=docker/dockerfile:1
#
# Single-container deploy: Vite frontend + Javalin backend served together on $PORT.
# Build context is this directory (CISN hackathon/). Run from here:
#   gcloud run deploy zai --source . --region asia-southeast1 ...

# ── Stage 1: build the Vite frontend ─────────────────────────────────────────
FROM node:20-alpine AS frontend
WORKDIR /build
COPY Frontend/package.json Frontend/package-lock.json ./
RUN npm ci
COPY Frontend/ ./
# Empty API_BASE → fetch("/api/...") resolves same-origin in the container.
ENV VITE_API_BASE=""
RUN npm run build

# ── Stage 2: compile the Java backend with javac (no Maven) ──────────────────
FROM eclipse-temurin:21-jdk AS backend
WORKDIR /build
COPY ["Hack-Project-1 (Z.Ai )/lib", "lib/"]
COPY ["Hack-Project-1 (Z.Ai )/src", "src/"]
RUN javac -d bin -cp "lib/*" src/com/hackproject/*.java

# ── Stage 3: runtime image ───────────────────────────────────────────────────
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=backend  /build/bin  ./bin
COPY --from=backend  /build/lib  ./lib
COPY --from=frontend /build/dist ./public
COPY ["Hack-Project-1 (Z.Ai )/tools.json",        "./tools.json"]
COPY ["Hack-Project-1 (Z.Ai )/CO_April20.csv",    "./CO_April20.csv"]
COPY ["Hack-Project-1 (Z.Ai )/warehouse_demo.db", "./warehouse_demo.db"]
ENV PORT=8080
EXPOSE 8080
# Linux classpath uses ':' not ';'. Cloud Run reads $PORT — WarehouseController handles that.
CMD ["sh", "-c", "java --enable-native-access=ALL-UNNAMED -cp \"lib/*:bin\" com.hackproject.WarehouseController"]
