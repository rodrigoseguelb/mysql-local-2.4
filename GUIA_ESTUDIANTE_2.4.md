# 🔐 Guía del Estudiante — Seguridad en el Pipeline CI con SonarCloud y Snyk
### Asignatura DevOps | Actividad 2.4 — Añadiendo seguridad a nuestro Pipeline

> **¿Qué vas a lograr?**
> Integrar dos herramientas profesionales de seguridad y calidad — **SonarCloud** y **Snyk** —
> en el pipeline de GitHub Actions del proyecto Spring Boot `bdget`.
> Al finalizar, tu pipeline ejecutará tests, medirá cobertura con JaCoCo, analizará la calidad
> del código con SonarCloud y detectará vulnerabilidades en dependencias e imagen Docker con Snyk,
> todo de forma completamente automatizada.

---

## 🖥️ Paso 0 — Verifica tu entorno local

Antes de comenzar, confirma que tienes instaladas todas las herramientas necesarias.
Abre una terminal y ejecuta cada comando:

```bash
java -version
# Esperado: openjdk version "17.x.x" o superior

mvn -version
# Esperado: Apache Maven 3.8.x o superior

git --version
# Esperado: git version 2.x.x

docker --version
# Esperado: Docker version 24.x.x o similar
```

| Herramienta | ¿Cómo verificar? | Necesario para |
|---|---|---|
| **Java JDK 17+** | `java -version` | Compilar y testear |
| **Maven 3.8+** | `mvn -version` | Ejecutar tests y análisis |
| **Git** | `git --version` | Publicar cambios al pipeline |
| **Docker Desktop** o **Podman** | `docker --version` | Build y escaneo de imagen |

Además necesitarás **cuentas gratuitas** en estas tres plataformas:

| Plataforma | URL | ¿Para qué? |
|---|---|---|
| **SonarCloud** | https://sonarcloud.io | Análisis de calidad de código |
| **Snyk** | https://snyk.io | Detección de vulnerabilidades |
| **GitHub** | https://github.com | Repositorio y pipeline (Actions) |

> 💡 Regístrate en SonarCloud y Snyk usando tu cuenta de GitHub para simplificar la integración.
> Los tres servicios tienen planes gratuitos para repositorios públicos.

---

## 📦 Paso 1 — Punto de partida: cómo estaba el proyecto antes de esta actividad

Esta actividad parte del proyecto `mysql-local-2.4`, que es una copia del proyecto de la
actividad anterior con los mismos archivos fuente y tests. Es importante que entiendas
el estado **antes** de aplicar los cambios.

### Estructura del proyecto

```text
mysql-local-2.4/
├── .github/
│   └── workflows/
│       └── main.yml          ← pipeline ORIGINAL (actividad anterior)
├── src/
│   ├── main/
│   │   ├── java/com/example/bdget/
│   │   │   ├── BdgetApplication.java
│   │   │   ├── controller/StudentController.java
│   │   │   ├── exception/GlobalExceptionHandler.java
│   │   │   ├── model/Student.java
│   │   │   ├── repository/StudentRepository.java
│   │   │   └── service/StudentService.java / StudentServiceImpl.java
│   │   └── resources/
│   │       └── application.properties
│   └── test/
│       └── java/com/example/bdget/
│           ├── BdgetApplicationTests.java
│           ├── controller/StudentControllerTest.java
│           ├── model/StudentModelTest.java
│           └── service/StudentServiceImplTest.java
├── Dockerfile
├── docker-compose.yml
└── pom.xml
```

### El pipeline ORIGINAL (actividad anterior)

El archivo `.github/workflows/main.yml` tenía 9 pasos sin ningún análisis de seguridad:

```yaml
name: CI — Build, Test y Push a DockerHub

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

jobs:
  build-test-push:
    runs-on: ubuntu-latest

    services:
      mysql:
        image: mysql:8.0
        env:
          MYSQL_ROOT_PASSWORD: root_pass
          MYSQL_DATABASE: bdget_db
          MYSQL_USER: bdget_user
          MYSQL_PASSWORD: bdget_pass
        ports:
          - 3306:3306
        options: >-
          --health-cmd="mysqladmin ping -h localhost -u root -proot_pass"
          --health-interval=10s
          --health-timeout=5s
          --health-retries=5

    steps:
      - name: Checkout del repositorio
        uses: actions/checkout@v4
        # ⚠️ SIN fetch-depth: 0 — SonarCloud no puede analizar sin historial completo

      - name: Configurar Java 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
          cache: maven

      - name: Ejecutar tests
        run: mvn test
        env:
          SPRING_DATASOURCE_URL: jdbc:mysql://localhost:3306/bdget_db?...
          SPRING_DATASOURCE_USERNAME: bdget_user
          SPRING_DATASOURCE_PASSWORD: bdget_pass

      - name: Generar reporte de cobertura
        run: mvn jacoco:report       # ⚠️ paso separado, ya no es necesario

      - name: Subir reporte de cobertura
        uses: actions/upload-artifact@v4
        with:
          name: jacoco-report
          path: target/site/jacoco/

      - name: Build del proyecto
        run: mvn package -DskipTests

      - name: Login en DockerHub
        uses: docker/login-action@v3
        with:
          username: ${{ secrets.DOCKERHUB_USERNAME }}
          password: ${{ secrets.DOCKERHUB_TOKEN }}

      - name: Build de la imagen Docker
        run: docker build -t ${{ secrets.DOCKERHUB_USERNAME }}/bdget-app:latest .

      - name: Push de la imagen a DockerHub
        run: docker push ${{ secrets.DOCKERHUB_USERNAME }}/bdget-app:latest
        # ⚠️ La imagen se publicaba sin ningún escaneo de seguridad
```

### El `pom.xml` ORIGINAL

La sección `<properties>` y `<build>` solo tenían Java y JaCoCo:

```xml
<name>bdget</name>

<properties>
    <java.version>17</java.version>
    <!-- ⚠️ Sin propiedades de SonarCloud -->
    <!-- ⚠️ Sin propiedades maven.compiler.* -->
</properties>

<build>
    <plugins>
        <!-- spring-boot-maven-plugin -->
        <!-- jacoco-maven-plugin -->
        <!-- ⚠️ Sin sonar-maven-plugin -->
    </plugins>
</build>
```

### Resumen de lo que le faltaba

| Qué faltaba | Impacto |
|---|---|
| `fetch-depth: 0` en checkout | SonarCloud no podía analizar el historial de cambios |
| Java 21 en el runner | SonarCloud exige JDK 21+ para ejecutar el scanner (desde 2025) |
| `maven.compiler.*` en `pom.xml` | Sin estas props el bytecode se compila en la versión del JDK del runner |
| `sonar-maven-plugin` en `pom.xml` | Maven no reconocía el prefijo `sonar` → error "No plugin found" |
| Propiedades `sonar.*` en `pom.xml` | Maven no sabía a qué proyecto de SonarCloud enviar el análisis |
| Paso de SonarCloud en el pipeline | No había análisis de calidad de código |
| Paso de Snyk (dependencias) | No se detectaban CVEs en las librerías del `pom.xml` |
| Paso de Snyk (imagen Docker) | La imagen se publicaba sin verificar vulnerabilidades del SO |

---

## 🔍 Paso 2 — ¿Qué son SonarCloud y Snyk?

### SonarCloud — Análisis de calidad de código

SonarCloud analiza tu código fuente y detecta problemas antes de que lleguen a producción:

| Categoría | ¿Qué detecta? | Ejemplo |
|---|---|---|
| **Bugs** | Errores que causarán fallos en runtime | Variable usada antes de inicializarse |
| **Vulnerabilities** | Puntos de entrada a ataques | Inyección SQL, contraseñas hardcodeadas |
| **Code Smells** | Código difícil de mantener | Método con 200 líneas, código duplicado |
| **Coverage** | Porcentaje de código cubierto por tests | Lee el XML de JaCoCo |
| **Duplication** | Bloques de código copiados entre clases | Misma lógica en 3 lugares distintos |

SonarCloud asigna una nota de **A a E** a cada categoría y puede configurarse para
**bloquear** un pull request si la calidad no cumple un umbral mínimo (**Quality Gate**).

### Snyk — Detección de vulnerabilidades

Snyk consulta su base de datos de CVEs y compara contra tus dependencias y tu imagen Docker:

| Lo que analiza | ¿Dónde busca? | ¿Qué reporta? |
|---|---|---|
| **Dependencias Maven** | Tu `pom.xml` | CVEs en cada librería y su versión corregida |
| **Imagen Docker** | Capas del `Dockerfile` | CVEs en paquetes del SO base (`eclipse-temurin`) |
| **Severidad** | Base CVSS | Critical / High / Medium / Low |

> 💡 En esta actividad usamos `continue-on-error: true` para que el pipeline no se
> detenga ante vulnerabilidades. En producción se eliminaría ese flag para **bloquear**
> deploys con vulnerabilidades críticas.

---

## ⚙️ Paso 3 — Configura SonarCloud

### 3a — Crea tu cuenta y el proyecto en SonarCloud

1. Ve a **https://sonarcloud.io** → **"Log in with GitHub"**
2. Click en **"+"** → **"Analyze new project"**
3. Selecciona el repositorio `mysql-local-2.4` → **"Set Up"**
4. Elige **"With GitHub Actions"** como método de análisis
5. SonarCloud te mostrará tres valores — **cópialos ahora**:
   - `SONAR_TOKEN` — token de autenticación (solo se muestra una vez)
   - `SONAR_PROJECT_KEY` — formato: `tu-usuario_mysql-local-2.4`
   - `SONAR_ORGANIZATION` — tu nombre de usuario en SonarCloud

> ⚠️ **Importante — desactiva el Análisis Automático:**
> SonarCloud activa el "Automatic Analysis" por defecto. Esto **chocará** con el
> análisis del pipeline y causará el error:
> `"You are running CI analysis while Automatic Analysis is enabled"`
>
> **Antes de hacer cualquier push**, desactívalo:
> 1. SonarCloud → tu proyecto → **Administration** → **Analysis Method**
> 2. Desactiva el toggle **"Automatic Analysis"** → deja solo **"GitHub Actions"**

### 3b — Modifica el `pom.xml` con las propiedades de SonarCloud

Abre `pom.xml` y reemplaza la sección completa de `<name>`, `<description>` y `<properties>`:

**ANTES (como estaba):**
```xml
<name>bdget</name>
<description>API REST de Estudiantes - Spring Boot + MySQL</description>

<properties>
    <java.version>17</java.version>
</properties>
```

**DESPUÉS (cómo debe quedar — copia exactamente esto):**
```xml
<name>mysql-local-2.4</name>
<description>API REST de Estudiantes - Spring Boot + MySQL + SonarCloud + Snyk</description>

<properties>
    <java.version>17</java.version>
    <!-- Fuerza compilación a bytecode Java 17 aunque el JDK del entorno sea 21 -->
    <maven.compiler.source>17</maven.compiler.source>
    <maven.compiler.target>17</maven.compiler.target>
    <maven.compiler.release>17</maven.compiler.release>
    <!-- SonarCloud -->
    <sonar.projectKey>TU-USUARIO_mysql-local-2.4</sonar.projectKey>
    <sonar.organization>TU-USUARIO</sonar.organization>
    <sonar.host.url>https://sonarcloud.io</sonar.host.url>
    <sonar.projectName>mysql-local-2.4</sonar.projectName>
</properties>
```

> ⚠️ Reemplaza `TU-USUARIO` con tu usuario real de GitHub/SonarCloud.
> Ejemplo: si tu usuario es `jperez`, los valores son:
> - `sonar.projectKey` → `jperez_mysql-local-2.4`
> - `sonar.organization` → `jperez`
> - `sonar.projectName` → `mysql-local-2.4` (este no cambia)

**¿Por qué `maven.compiler.*`?**
El pipeline usará JDK 21 (que SonarCloud exige), pero sin estas propiedades Maven
compilaría el código en bytecode Java 21. Con ellas, el `.jar` final sigue siendo
compatible con Java 17 aunque el JDK del runner sea 21.

**¿Por qué `sonar.projectName`?**
Sin esta propiedad, SonarCloud toma el `<name>` del `pom.xml` como nombre para mostrar.
Si viene copiado de otro proyecto (`bdget`), el proyecto aparecerá con ese nombre incorrecto
en el dashboard. `sonar.projectName` fuerza el nombre visible a `mysql-local-2.4`.

### 3c — Agrega el `sonar-maven-plugin` al `pom.xml`

Dentro de `<build><plugins>`, agrega el plugin de SonarCloud **antes** del bloque de JaCoCo:

**ANTES:**
```xml
<build>
    <plugins>
        <!-- spring-boot-maven-plugin ... -->

        <!-- JaCoCo ... -->
    </plugins>
</build>
```

**DESPUÉS:**
```xml
<build>
    <plugins>
        <!-- spring-boot-maven-plugin ... -->

        <!-- SonarCloud: análisis de calidad de código -->
        <!-- v3.9.x es la última versión compatible con Java 17 como target -->
        <plugin>
            <groupId>org.sonarsource.scanner.maven</groupId>
            <artifactId>sonar-maven-plugin</artifactId>
            <version>3.9.1.2184</version>
        </plugin>

        <!-- JaCoCo ... -->
    </plugins>
</build>
```

> ⚠️ **Versión del plugin:** usa exactamente `3.9.1.2184`.
> Las versiones `3.10.x` y `3.11.x` también requieren Java 21 en el runner
> para ejecutar el scanner, pero con versiones más nuevas del scanner embedido
> dentro del plugin que pueden tener incompatibilidades adicionales.
> La `3.9.1.2184` es la más estable para este setup.

---

## ⚙️ Paso 4 — Configura Snyk

### 4a — Crea tu cuenta y obtén el token

1. Ve a **https://snyk.io** → **"Sign up with GitHub"**
2. Click en tu **avatar** → **"Account settings"**
3. En **"Auth Token"** → click en **"click to show"** → copia el token

### 4b — (Opcional) Prueba Snyk localmente

```bash
npm install -g snyk    # requiere Node.js
snyk auth              # abre el navegador para autenticarte
cd mysql-local-2.4
snyk test --all-projects
```

---

## 🔑 Paso 5 — Configura los Secrets en GitHub

Ve a tu repositorio → **Settings** → **Secrets and variables** → **Actions** → **"New repository secret"**

| Secret | De dónde obtenerlo | ¿Ya lo tenías? |
|---|---|---|
| `SONAR_TOKEN` | SonarCloud → tu proyecto → Analysis Method → GitHub Actions | ❌ Nuevo |
| `SONAR_PROJECT_KEY` | Mismo lugar (ej: `jperez_mysql-local-2.4`) | ❌ Nuevo |
| `SONAR_ORGANIZATION` | Mismo lugar (ej: `jperez`) | ❌ Nuevo |
| `SNYK_TOKEN` | Snyk → Account settings → Auth Token | ❌ Nuevo |
| `DOCKERHUB_USERNAME` | Tu usuario de DockerHub | ✅ Actividad anterior |
| `DOCKERHUB_TOKEN` | Token de acceso de DockerHub | ✅ Actividad anterior |

Verifica que los 6 están configurados con `gh` CLI:

```bash
gh secret list
```

Resultado esperado:
```
DOCKERHUB_TOKEN       Updated recently
DOCKERHUB_USERNAME    Updated recently
SNYK_TOKEN            Updated recently
SONAR_ORGANIZATION    Updated recently
SONAR_PROJECT_KEY     Updated recently
SONAR_TOKEN           Updated recently
```

> 📸 **Toma una captura de pantalla de esta lista** — es uno de los entregables.

---

## 🔄 Paso 6 — El pipeline completo actualizado

Reemplaza **todo** el contenido de `.github/workflows/main.yml` con lo siguiente.

> 🔑 **Cambio clave respecto a la actividad anterior:** el runner usa **Java 21**
> (no Java 17) porque SonarCloud lo exige para ejecutar el scanner.
> El código del proyecto sigue compilándose en bytecode Java 17 gracias a las
> propiedades `maven.compiler.*` del `pom.xml`.

```yaml
name: CI — Tests, Calidad y Seguridad

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

jobs:
  build-test-analyze:
    runs-on: ubuntu-latest

    # ── Servicio MySQL para tests ──
    services:
      mysql:
        image: mysql:8.0
        env:
          MYSQL_ROOT_PASSWORD: root_pass
          MYSQL_DATABASE: bdget_db
          MYSQL_USER: bdget_user
          MYSQL_PASSWORD: bdget_pass
        ports:
          - 3306:3306
        options: >-
          --health-cmd="mysqladmin ping -h localhost -u root -proot_pass"
          --health-interval=10s
          --health-timeout=5s
          --health-retries=5

    steps:
      # ── 1. Descargar el código ──
      - name: Checkout del repositorio
        uses: actions/checkout@v4
        with:
          fetch-depth: 0          # SonarCloud necesita el historial completo

      # ── 2. Configurar Java 21 ──
      # SonarCloud requiere Java 21+ para ejecutar el scanner.
      # El código se sigue compilando en bytecode Java 17 (ver pom.xml).
      - name: Configurar Java 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: maven

      # ── 3. Ejecutar tests + generar reporte JaCoCo ──
      - name: Ejecutar tests
        run: mvn test
        env:
          SPRING_DATASOURCE_URL: jdbc:mysql://localhost:3306/bdget_db?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true
          SPRING_DATASOURCE_USERNAME: bdget_user
          SPRING_DATASOURCE_PASSWORD: bdget_pass

      # ── 4. Subir reporte de cobertura como artefacto ──
      - name: Subir reporte JaCoCo
        uses: actions/upload-artifact@v4
        with:
          name: jacoco-report
          path: target/site/jacoco/

      # ── 5. Analizar con SonarCloud ──
      - name: Análisis SonarCloud
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}   # automático, no lo crees tú
          SONAR_TOKEN: ${{ secrets.SONAR_TOKEN }}
        run: |
          mvn sonar:sonar \
            -Dsonar.projectKey=${{ secrets.SONAR_PROJECT_KEY }} \
            -Dsonar.organization=${{ secrets.SONAR_ORGANIZATION }} \
            -Dsonar.host.url=https://sonarcloud.io \
            -Dsonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml

      # ── 6. Escanear dependencias con Snyk ──
      - name: Escaneo de vulnerabilidades Snyk (dependencias)
        uses: snyk/actions/maven@master
        continue-on-error: true           # no bloquea el pipeline si hay vulnerabilidades
        env:
          SNYK_TOKEN: ${{ secrets.SNYK_TOKEN }}
        with:
          args: --severity-threshold=high  # solo reporta Critical y High

      # ── 7. Compilar el .jar ──
      - name: Build del proyecto
        run: mvn package -DskipTests

      # ── 8. Login en DockerHub ──
      - name: Login en DockerHub
        uses: docker/login-action@v3
        with:
          username: ${{ secrets.DOCKERHUB_USERNAME }}
          password: ${{ secrets.DOCKERHUB_TOKEN }}

      # ── 9. Construir imagen Docker ──
      - name: Build de la imagen Docker
        run: docker build -t ${{ secrets.DOCKERHUB_USERNAME }}/bdget-app:latest .

      # ── 10. Escanear imagen Docker con Snyk ──
      - name: Escaneo de imagen Docker con Snyk
        uses: snyk/actions/docker@master
        continue-on-error: true
        env:
          SNYK_TOKEN: ${{ secrets.SNYK_TOKEN }}
        with:
          image: ${{ secrets.DOCKERHUB_USERNAME }}/bdget-app:latest
          args: --severity-threshold=high

      # ── 11. Publicar imagen en DockerHub ──
      - name: Push de la imagen a DockerHub
        run: docker push ${{ secrets.DOCKERHUB_USERNAME }}/bdget-app:latest
```

---

## 🔬 Paso 7 — Entiende cada cambio en detalle

### Cambio 1 — `fetch-depth: 0` en el Checkout

```yaml
# ANTES: sin opciones
- uses: actions/checkout@v4

# DESPUÉS
- uses: actions/checkout@v4
  with:
    fetch-depth: 0    # descarga TODO el historial de commits
```

SonarCloud necesita el historial completo para distinguir "código nuevo" de "código existente"
y aplicar el Quality Gate solo al código reciente. Sin esto, el análisis falla o da
métricas incorrectas.

---

### Cambio 2 — Java 21 en el runner (con compilación Java 17 en el código)

```yaml
# ANTES
java-version: '17'

# DESPUÉS
java-version: '21'   # SonarCloud exige JDK 21+ para ejecutar el scanner
```

Desde 2025, SonarCloud rechaza análisis enviados desde JDK 17 con el error:
`"Java 17 is not supported. Please upgrade to Java 21 or newer"`.

Para que el `.jar` resultante siga siendo compatible con entornos Java 17,
se agregan estas propiedades en el `pom.xml`:

```xml
<maven.compiler.source>17</maven.compiler.source>
<maven.compiler.target>17</maven.compiler.target>
<maven.compiler.release>17</maven.compiler.release>
```

El JDK 21 compila el código, pero genera bytecode nivel 17.

---

### Cambio 3 — `sonar-maven-plugin` declarado en `pom.xml`

```xml
<plugin>
    <groupId>org.sonarsource.scanner.maven</groupId>
    <artifactId>sonar-maven-plugin</artifactId>
    <version>3.9.1.2184</version>
</plugin>
```

Sin esta declaración, el comando `mvn sonar:sonar` falla con:
`"No plugin found for prefix 'sonar' in the current project"`.
Maven necesita ver el plugin en el `pom.xml` para asociar el prefijo `sonar`
con el `groupId` correcto.

---

### Cambio 4 — Desactivar Automatic Analysis en SonarCloud

SonarCloud activa por defecto el "Automatic Analysis" al vincular un repositorio de GitHub.
Cuando el pipeline también ejecuta `mvn sonar:sonar`, hay dos analizadores enviando
resultados al mismo proyecto y SonarCloud lo rechaza con:
`"You are running CI analysis while Automatic Analysis is enabled"`.

**Solución:** SonarCloud → tu proyecto → Administration → Analysis Method →
desactivar **"Automatic Analysis"**.

Usamos CI (pipeline) en lugar de Automatic Analysis porque necesitamos enviar el reporte
de cobertura de JaCoCo — el Automatic Analysis no puede leer ese archivo.

---

### Cambio 5 — `sonar.projectName` para el nombre visible en SonarCloud

```xml
<sonar.projectName>mysql-local-2.4</sonar.projectName>
```

Sin esta propiedad, SonarCloud usa `<name>` del `pom.xml` como nombre visible.
Si el proyecto fue copiado de uno anterior (donde `<name>` era `bdget`), aparecería
con el nombre incorrecto en el dashboard aunque el `projectKey` sea correcto.

SonarCloud resuelve el nombre en este orden de prioridad:
1. `sonar.projectName` → máxima prioridad ✅
2. `<name>` en `pom.xml`
3. `sonar.projectKey` → fallback

---

### Cambio 6 — Nuevo Paso 5: Análisis SonarCloud

```yaml
- name: Análisis SonarCloud
  env:
    GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
    SONAR_TOKEN: ${{ secrets.SONAR_TOKEN }}
  run: |
    mvn sonar:sonar \
      -Dsonar.projectKey=${{ secrets.SONAR_PROJECT_KEY }} \
      -Dsonar.organization=${{ secrets.SONAR_ORGANIZATION }} \
      -Dsonar.host.url=https://sonarcloud.io \
      -Dsonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml
```

| Parámetro | Qué hace |
|---|---|
| `GITHUB_TOKEN` | Token automático de Actions — no hay que crearlo |
| `SONAR_TOKEN` | Autentica Maven contra SonarCloud |
| `-Dsonar.projectKey` | Identifica el proyecto en SonarCloud |
| `-Dsonar.organization` | Identifica tu organización en SonarCloud |
| `-Dsonar.coverage.jacoco.xmlReportPaths` | Ruta al reporte de cobertura de JaCoCo |

---

### Cambio 7 — Nuevo Paso 6: Snyk en dependencias Maven

```yaml
- name: Escaneo de vulnerabilidades Snyk (dependencias)
  uses: snyk/actions/maven@master
  continue-on-error: true
  env:
    SNYK_TOKEN: ${{ secrets.SNYK_TOKEN }}
  with:
    args: --severity-threshold=high
```

`snyk/actions/maven` lee el `pom.xml` y consulta la base de CVEs de Snyk.
`--severity-threshold=high` filtra solo vulnerabilidades **High** y **Critical**.
`continue-on-error: true` permite que el pipeline siga aunque encuentre problemas.

---

### Cambio 8 — Nuevo Paso 10: Snyk en imagen Docker

```yaml
- name: Escaneo de imagen Docker con Snyk
  uses: snyk/actions/docker@master
  continue-on-error: true
  env:
    SNYK_TOKEN: ${{ secrets.SNYK_TOKEN }}
  with:
    image: ${{ secrets.DOCKERHUB_USERNAME }}/bdget-app:latest
    args: --severity-threshold=high
```

Escanea los paquetes del SO dentro de la imagen `eclipse-temurin:17-jre`.
Va **después** del `docker build` y **antes** del `docker push` — así se
verifica la imagen antes de publicarla.

---

## 🚀 Paso 8 — Ejecuta el pipeline completo

### Verifica localmente antes de hacer push

```bash
# Confirma que estás en la rama correcta
git branch
# Debe mostrar: * main

# Confirma el historial (importante para SonarCloud)
git log --oneline -5

# Ejecuta los tests localmente
cd mysql-local-2.4
mvn test
# Debe terminar con: BUILD SUCCESS
```

### Publica los cambios

```bash
git add pom.xml .github/workflows/main.yml
git commit -m "feat: integra SonarCloud y Snyk en el pipeline CI"
git push origin main
```

### Observa la ejecución

```bash
# Progreso en tiempo real desde la terminal
gh run watch

# O abre el navegador
gh run view --web
```

Secuencia esperada con tiempos aproximados:

```
✅  1. Checkout del repositorio          ~5s
✅  2. Configurar Java 21                ~15s
✅  3. Ejecutar tests                    ~60s
✅  4. Subir reporte JaCoCo              ~5s   → artefacto disponible
✅  5. Análisis SonarCloud               ~90s  → resultados en sonarcloud.io
✅  6. Escaneo Snyk (dependencias)       ~30s  → resultados en snyk.io
✅  7. Build del proyecto                ~30s
✅  8. Login en DockerHub                ~5s
✅  9. Build de la imagen Docker         ~120s
✅ 10. Escaneo Snyk (imagen Docker)      ~45s  → resultados en snyk.io
✅ 11. Push de la imagen a DockerHub     ~30s
```

> ⏱️ Primera ejecución: ~7-10 minutos. Las siguientes son más rápidas gracias al caché de Maven.

---

## 📊 Paso 9 — Interpreta los resultados en SonarCloud

### Dashboard principal

```
┌─────────────────────────────────────────────────────────────┐
│  Quality Gate: ✅ PASSED  (o ❌ FAILED)                       │
├──────────┬──────────┬──────────┬──────────┬─────────────────┤
│  Bugs    │  Vulner. │  Smells  │ Coverage │  Duplication    │
│   0  A   │   0  A   │   X  B   │  XX.X%   │    X.X%         │
└──────────┴──────────┴──────────┴──────────┴─────────────────┘
```

### Quality Gate — qué condiciones aplica

| Condición | Umbral | Aplica a |
|---|---|---|
| Bugs nuevos | 0 | Código nuevo |
| Vulnerabilidades nuevas | 0 | Código nuevo |
| Cobertura | ≥ 80% | Código nuevo |
| Duplicación | < 3% | Código nuevo |

### Navegación en el dashboard

- **"Issues"** → lista todos los code smells, bugs y vulnerabilidades con archivo y línea exacta
- **"Coverage"** → porcentaje de líneas cubiertas, integrado visualmente sobre el código
- **"Security Hotspots"** → puntos que merecen revisión de seguridad manual

> 📸 Toma capturas del dashboard y de la sección Issues.

---

## 🔎 Paso 10 — Interpreta los resultados en Snyk

### En GitHub Actions

Expande el paso **"Escaneo de vulnerabilidades Snyk (dependencias)"**:

```text
Testing /github/workspace...

✗ High severity vulnerability found in com.some.library:name
  CVE: CVE-2023-XXXXX
  Introduced through: com.some.library:name@X.X.X
  Fixed in: X.Y.Z

Tested 42 dependencies for known issues, found N issues.
```

Sin vulnerabilidades:
```text
✓ Tested 42 dependencies for known issues, no vulnerable paths found.
```

### En el dashboard de Snyk

**https://snyk.io** → **Projects** → tu repositorio `mysql-local-2.4`

| Campo | Significado |
|---|---|
| **Severity** | Critical / High / Medium / Low |
| **CVE** | Identificador oficial de la vulnerabilidad |
| **Introduced through** | Librería de tu `pom.xml` que la introduce |
| **Fixed in** | Versión donde fue corregido (si existe) |

> 📸 Toma capturas de ambos pasos de Snyk en GitHub Actions.

---

## 🛑 Paso 11 — Solución de problemas frecuentes

### ❌ "No plugin found for prefix 'sonar'"

**Causa:** El `sonar-maven-plugin` no está declarado en el `pom.xml`.

**Solución:** Agrega el plugin dentro de `<build><plugins>`:
```xml
<plugin>
    <groupId>org.sonarsource.scanner.maven</groupId>
    <artifactId>sonar-maven-plugin</artifactId>
    <version>3.9.1.2184</version>
</plugin>
```

---

### ❌ "Java 17 is not supported. Please upgrade to Java 21"

**Causa:** El runner del pipeline usa Java 17 pero SonarCloud ahora exige Java 21+.

**Solución en `main.yml`:** Cambia `java-version: '17'` a `java-version: '21'`.

**Solución en `pom.xml`:** Agrega las propiedades del compilador para mantener bytecode Java 17:
```xml
<maven.compiler.source>17</maven.compiler.source>
<maven.compiler.target>17</maven.compiler.target>
<maven.compiler.release>17</maven.compiler.release>
```

---

### ❌ "You are running CI analysis while Automatic Analysis is enabled"

**Causa:** SonarCloud tiene activado el Análisis Automático y el pipeline también intenta analizar. Los dos chocan.

**Solución:** SonarCloud → tu proyecto → **Administration** → **Analysis Method** →
desactivar el toggle **"Automatic Analysis"**.

---

### ❌ "Project not found" en SonarCloud

**Causa:** El `SONAR_PROJECT_KEY` en el Secret no coincide con el del proyecto en SonarCloud.

**Solución:** Verifica el valor exacto en la URL de tu proyecto:
```
https://sonarcloud.io/project/overview?id=TU-USUARIO_mysql-local-2.4
                                           ^^^^^^^^^^^^^^^^^^^^^^^^^^^
                                           este es tu SONAR_PROJECT_KEY
```

---

### ❌ El proyecto aparece con nombre incorrecto en SonarCloud (ej: "bdget")

**Causa:** SonarCloud usa `<name>` del `pom.xml` si no existe `sonar.projectName`.

**Solución:** Agrega en `<properties>` del `pom.xml`:
```xml
<sonar.projectName>mysql-local-2.4</sonar.projectName>
```
Y actualiza también `<name>`:
```xml
<name>mysql-local-2.4</name>
```

---

### ❌ "Missing SNYK_TOKEN"

**Causa:** El Secret `SNYK_TOKEN` no está configurado en GitHub.

**Solución:**
```bash
gh secret set SNYK_TOKEN
gh secret list   # verifica que aparece
```

---

### ❌ Quality Gate falla por cobertura baja

SonarCloud requiere ≥ 80% en código nuevo. Si falla:

```bash
mvn test
open target/site/jacoco/index.html   # macOS
# Identifica clases con baja cobertura y agrega tests
```

---

## 📊 Paso 12 — Resumen de comandos

| Acción | Comando |
|---|---|
| Ejecutar tests + cobertura | `mvn test` |
| Limpiar y recompilar todo | `mvn clean test` |
| Ejecutar análisis Sonar local | `mvn sonar:sonar -Dsonar.token=TU_TOKEN` |
| Instalar Snyk CLI | `npm install -g snyk` |
| Escanear dependencias con Snyk | `snyk test --all-projects` |
| Escanear imagen con Snyk | `snyk container test usuario/bdget-app:latest` |
| Ver reporte JaCoCo (macOS) | `open target/site/jacoco/index.html` |
| Ver estado git | `git status` |
| Ver historial | `git log --oneline -5` |
| Agregar cambios | `git add pom.xml .github/workflows/main.yml` |
| Commit | `git commit -m "feat: integra SonarCloud y Snyk"` |
| Push | `git push origin main` |
| Ver pipeline en tiempo real | `gh run watch` |
| Abrir pipeline en navegador | `gh run view --web` |
| Listar secrets | `gh secret list` |

---

## ✅ Lista de verificación — Entregables de la Actividad 2.4

### Configuración previa
- [ ] Cuenta **SonarCloud** creada y vinculada con GitHub
- [ ] Cuenta **Snyk** creada y vinculada con GitHub
- [ ] **Automatic Analysis desactivado** en SonarCloud (Administration → Analysis Method)
- [ ] **`pom.xml` modificado** con:
  - `<name>mysql-local-2.4</name>`
  - `maven.compiler.source/target/release` = `17`
  - `sonar.projectKey`, `sonar.organization`, `sonar.host.url`, `sonar.projectName`
  - `sonar-maven-plugin` versión `3.9.1.2184`
- [ ] **`.github/workflows/main.yml` actualizado** con Java 21 y los 11 pasos

### Secrets en GitHub
- [ ] **6 Secrets configurados**: `SONAR_TOKEN`, `SONAR_PROJECT_KEY`, `SONAR_ORGANIZATION`, `SNYK_TOKEN`, `DOCKERHUB_USERNAME`, `DOCKERHUB_TOKEN`
- [ ] 📸 **Captura de la lista de Secrets** (Settings → Secrets → Actions) — sin mostrar valores

### Pipeline
- [ ] 📸 **Captura del pipeline en GitHub Actions** con los **11 pasos en verde** ✅
- [ ] 📸 **Captura de la sección Artifacts** mostrando el artefacto `jacoco-report`

### SonarCloud
- [ ] 📸 **Captura del dashboard** mostrando Quality Gate + métricas (Bugs, Vulnerabilities, Coverage)
- [ ] 📸 **Captura de la sección "Issues"** (aunque muestre `0 open issues`)

### Snyk
- [ ] 📸 **Captura del paso "Escaneo Snyk (dependencias)"** en GitHub Actions
- [ ] 📸 **Captura del paso "Escaneo Snyk (imagen Docker)"** en GitHub Actions

---

> **Total: 7 capturas de pantalla** que demuestran la implementación completa.

---

*Guía del Estudiante — Asignatura DevOps | Actividad 2.4*
*Proyecto: `mysql-local-2.4` | Spring Boot + MySQL + GitHub Actions + SonarCloud + Snyk*
