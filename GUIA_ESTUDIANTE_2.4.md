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
# Esperado: openjdk version "17.x.x" o similar

mvn -version
# Esperado: Apache Maven 3.8.x o superior

git --version
# Esperado: git version 2.x.x

docker --version
# Esperado: Docker version 24.x.x o similar
# (también vale: podman --version)
```

Si algún comando falla, instala la herramienta antes de continuar.

| Herramienta | ¿Cómo verificar? | Necesario para |
|---|---|---|
| **Java JDK 17** | `java -version` | Compilar y testear |
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

Esta actividad parte del proyecto `mysql-local-2.4` que ya tienes funcionando.
Es importante que entiendas el estado **antes** de aplicar los cambios.

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

Este era el contenido de `.github/workflows/main.yml` **antes** de esta actividad.
Tenía 9 pasos: checkout → Java → tests → JaCoCo → subir artefacto → build jar → login Docker → build imagen → push imagen.

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
        # ⚠️ SIN fetch-depth: 0 — SonarCloud no podía funcionar así

      - name: Configurar Java 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
          cache: maven

      - name: Ejecutar tests
        run: mvn test
        env:
          SPRING_DATASOURCE_URL: jdbc:mysql://localhost:3306/bdget_db?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true
          SPRING_DATASOURCE_USERNAME: bdget_user
          SPRING_DATASOURCE_PASSWORD: bdget_pass

      - name: Generar reporte de cobertura
        run: mvn jacoco:report
        # ⚠️ Paso separado — en la versión nueva queda integrado en mvn test

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
        # ⚠️ Sin análisis de seguridad — la imagen se publicaba sin ningún escaneo
```

### El `pom.xml` ORIGINAL (actividad anterior)

La sección `<properties>` solo tenía la versión de Java:

```xml
<properties>
    <java.version>17</java.version>
    <!-- ⚠️ Sin propiedades de SonarCloud -->
</properties>
```

**Resumen de lo que le faltaba al proyecto:**

| Qué faltaba | Impacto |
|---|---|
| `fetch-depth: 0` en checkout | SonarCloud no podía analizar el historial de cambios |
| Análisis SonarCloud | No había medición de calidad de código |
| Escaneo Snyk de dependencias | No se detectaban CVEs en las librerías del `pom.xml` |
| Escaneo Snyk de imagen Docker | La imagen se publicaba sin verificar vulnerabilidades del SO |
| Propiedades `sonar.*` en `pom.xml` | Maven no sabía a qué proyecto de SonarCloud enviar el análisis |

---

## 🔍 Paso 2 — ¿Qué son SonarCloud y Snyk?

### SonarCloud — Análisis de calidad de código

SonarCloud es una plataforma en la nube que analiza tu código fuente y detecta problemas
antes de que lleguen a producción:

| Categoría | ¿Qué detecta? | Ejemplo |
|---|---|---|
| **Bugs** | Errores que causarán fallos en runtime | Variable usada antes de inicializarse |
| **Vulnerabilities** | Puntos de entrada a ataques | Inyección SQL, contraseñas hardcodeadas |
| **Code Smells** | Código que funciona pero es difícil de mantener | Método con 200 líneas, código duplicado |
| **Coverage** | Porcentaje de código cubierto por tests | Lee el XML de JaCoCo |
| **Duplication** | Bloques de código copiados entre clases | Misma lógica en 3 lugares distintos |

SonarCloud asigna una nota de **A a E** a cada categoría y puede configurarse para
**bloquear** un pull request si la calidad no cumple un umbral mínimo (**Quality Gate**).

### Snyk — Detección de vulnerabilidades

Snyk consulta su base de datos de CVEs (vulnerabilidades públicas conocidas) y compara
contra tus dependencias y los paquetes de tu imagen Docker:

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

1. Abre tu navegador y ve a **https://sonarcloud.io**
2. Haz click en **"Log in"** → selecciona **"Log in with GitHub"**
3. Autoriza el acceso de SonarCloud a tu cuenta de GitHub
4. Una vez dentro, haz click en el botón **"+"** (arriba a la derecha) → **"Analyze new project"**
5. SonarCloud mostrará tus repositorios de GitHub. Busca y selecciona **`bdget`**
6. Haz click en **"Set Up"**
7. En la pantalla siguiente, elige **"With GitHub Actions"** como método de análisis
8. SonarCloud te mostrará una pantalla con tres valores importantes — **cópialos ahora**:
   - `SONAR_TOKEN` — token de autenticación (solo se muestra una vez)
   - `SONAR_PROJECT_KEY` — identificador único del proyecto (formato: `tu-usuario_bdget`)
   - `SONAR_ORGANIZATION` — tu nombre de organización en SonarCloud (generalmente tu usuario de GitHub)

> ⚠️ Guarda estos tres valores en un lugar seguro (bloc de notas, gestor de contraseñas).
> El `SONAR_TOKEN` no se puede ver de nuevo después de cerrar esa ventana.

### 3b — Modifica el `pom.xml` con las propiedades de SonarCloud

Abre el archivo [`pom.xml`](pom.xml) en tu editor. Localiza la sección `<properties>`:

**ANTES (como estaba):**
```xml
<properties>
    <java.version>17</java.version>
</properties>
```

**DESPUÉS (cómo debe quedar):**
```xml
<properties>
    <java.version>17</java.version>
    <!-- SonarCloud -->
    <sonar.projectKey>TU-USUARIO_bdget</sonar.projectKey>
    <sonar.organization>TU-USUARIO-SONARCLOUD</sonar.organization>
    <sonar.host.url>https://sonarcloud.io</sonar.host.url>
</properties>
```

> ⚠️ Reemplaza `TU-USUARIO_bdget` con tu `SONAR_PROJECT_KEY` real
> y `TU-USUARIO-SONARCLOUD` con tu `SONAR_ORGANIZATION` real.
>
> **Ejemplo real:** si tu usuario de GitHub es `jperez`, los valores serían:
> - `sonar.projectKey` → `jperez_bdget`
> - `sonar.organization` → `jperez`

Verifica el cambio:
```bash
# Muestra las líneas de properties en el pom.xml
grep -A 6 "<properties>" pom.xml
```

---

## ⚙️ Paso 4 — Configura Snyk

### 4a — Crea tu cuenta y obtén el token de Snyk

1. Ve a **https://snyk.io**
2. Haz click en **"Sign up"** → selecciona **"Sign up with GitHub"**
3. Autoriza el acceso de Snyk a tu cuenta de GitHub
4. Una vez dentro del dashboard, haz click en tu **avatar** (esquina superior derecha)
5. Selecciona **"Account settings"**
6. En la sección **"Auth Token"**, haz click en **"click to show"**
7. Copia el token que aparece — lo necesitarás en el siguiente paso

### 4b — (Opcional) Prueba Snyk localmente antes del pipeline

Si tienes Node.js instalado, puedes verificar que Snyk funciona en tu máquina:

```bash
# Instalar Snyk CLI globalmente
npm install -g snyk

# Autenticarse (abrirá el navegador)
snyk auth

# Ir al directorio del proyecto y escanear dependencias
cd mysql-local-2.4
snyk test --all-projects

# Ver el resultado — mostrará las vulnerabilidades encontradas en pom.xml
```

> 💡 Este paso es opcional. El pipeline ejecutará el escaneo automáticamente.
> Hacerlo local te sirve para ver los resultados antes de hacer push.

---

## 🔑 Paso 5 — Configura todos los Secrets en GitHub

Los Secrets son variables cifradas que GitHub Actions usa durante el pipeline.
Nunca aparecen en los logs y no pueden ser leídas por nadie después de guardarse.

### Cómo agregar un Secret

1. Ve a tu repositorio en GitHub
2. Haz click en **"Settings"** (pestaña superior)
3. En el menú lateral izquierdo, haz click en **"Secrets and variables"** → **"Actions"**
4. Haz click en **"New repository secret"**
5. Ingresa el nombre y el valor → click en **"Add secret"**
6. Repite para cada secret

### Secrets que debes tener configurados

| Secret | De dónde obtenerlo | ¿Ya lo tenías? |
|---|---|---|
| `SONAR_TOKEN` | SonarCloud → Analyze new project → With GitHub Actions | ❌ Nuevo |
| `SONAR_PROJECT_KEY` | SonarCloud → mismo lugar (ej: `jperez_bdget`) | ❌ Nuevo |
| `SONAR_ORGANIZATION` | SonarCloud → mismo lugar (ej: `jperez`) | ❌ Nuevo |
| `SNYK_TOKEN` | Snyk → Account settings → Auth Token | ❌ Nuevo |
| `DOCKERHUB_USERNAME` | Tu usuario de DockerHub | ✅ Actividad anterior |
| `DOCKERHUB_TOKEN` | Token de acceso de DockerHub | ✅ Actividad anterior |

> ✅ `DOCKERHUB_USERNAME` y `DOCKERHUB_TOKEN` ya los tenías de la actividad anterior.
> Solo necesitas agregar los 4 nuevos.

### Verificar que los secrets están configurados

Después de agregar todos los secrets, la pantalla de **Secrets and variables → Actions**
debe mostrar 6 secrets (los nombres, no los valores):

```text
DOCKERHUB_TOKEN         Updated X days ago
DOCKERHUB_USERNAME      Updated X days ago
SNYK_TOKEN              Updated X days ago
SONAR_ORGANIZATION      Updated X days ago
SONAR_PROJECT_KEY       Updated X days ago
SONAR_TOKEN             Updated X days ago
```

> 📸 **Toma una captura de pantalla de esta lista** — es uno de los entregables.

---

## 🔄 Paso 6 — Actualiza el pipeline: los cambios en `main.yml`

Este es el paso más importante de la actividad. Vas a reemplazar el contenido completo
del archivo `.github/workflows/main.yml`.

### Diferencias clave entre el pipeline ANTERIOR y el NUEVO

| # | Pipeline ANTERIOR | Pipeline NUEVO |
|---|---|---|
| Job | `build-test-push` | `build-test-analyze` |
| Nombre | `CI — Build, Test y Push a DockerHub` | `CI — Tests, Calidad y Seguridad` |
| Checkout | Sin `fetch-depth` | Con `fetch-depth: 0` (historial completo) |
| JaCoCo | Paso separado `mvn jacoco:report` | Integrado en `mvn test` (fase `test`) |
| SonarCloud | ❌ No existía | ✅ Nuevo paso 5 |
| Snyk deps | ❌ No existía | ✅ Nuevo paso 6 |
| Snyk Docker | ❌ No existía | ✅ Nuevo paso 10 |
| Total pasos | 9 pasos | 11 pasos |

### El pipeline NUEVO completo

Reemplaza **todo** el contenido de `.github/workflows/main.yml` con lo siguiente:

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

      # ── 2. Configurar Java 17 ──
      - name: Configurar Java 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
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

## 🔬 Paso 7 — Entiende cada cambio nuevo en detalle

### Cambio 1 — `fetch-depth: 0` en el Checkout

**ANTES:**
```yaml
- name: Checkout del repositorio
  uses: actions/checkout@v4
  # Sin opciones adicionales: descarga solo el último commit
```

**DESPUÉS:**
```yaml
- name: Checkout del repositorio
  uses: actions/checkout@v4
  with:
    fetch-depth: 0    # descarga TODO el historial de commits
```

**¿Por qué es necesario?**
SonarCloud distingue entre "código nuevo" (cambios recientes) y "código existente".
Para hacer ese cálculo necesita el historial completo de commits.
Sin `fetch-depth: 0`, SonarCloud descarga solo el último commit y no puede calcular
las métricas de calidad gate correctamente. El análisis puede fallar o dar resultados incorrectos.

---

### Cambio 2 — JaCoCo integrado en `mvn test`

**ANTES (dos pasos separados):**
```yaml
- name: Ejecutar tests
  run: mvn test

- name: Generar reporte de cobertura
  run: mvn jacoco:report   # ← paso extra que ya no es necesario
```

**DESPUÉS (un solo paso):**
```yaml
- name: Ejecutar tests
  run: mvn test
  # JaCoCo genera el reporte automáticamente durante la fase "test"
  # porque en el pom.xml el goal "report" está atado a la fase "test"
```

**¿Por qué funciona?**
En el `pom.xml`, el plugin JaCoCo tiene la ejecución `report` atada a la fase `test`:
```xml
<execution>
    <id>report</id>
    <phase>test</phase>          <!-- se ejecuta durante "mvn test" -->
    <goals><goal>report</goal></goals>
</execution>
```
Así, al ejecutar `mvn test` se generan automáticamente los archivos en `target/site/jacoco/`,
incluyendo `jacoco.xml` que SonarCloud necesita para mostrar la cobertura.

---

### Cambio 3 — Nuevo Paso 5: Análisis SonarCloud

```yaml
- name: Análisis SonarCloud
  env:
    GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}   # token automático de GitHub Actions
    SONAR_TOKEN: ${{ secrets.SONAR_TOKEN }}      # tu token de SonarCloud
  run: |
    mvn sonar:sonar \
      -Dsonar.projectKey=${{ secrets.SONAR_PROJECT_KEY }} \
      -Dsonar.organization=${{ secrets.SONAR_ORGANIZATION }} \
      -Dsonar.host.url=https://sonarcloud.io \
      -Dsonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml
```

**Explicación de cada parámetro:**

| Parámetro | ¿Qué hace? |
|---|---|
| `GITHUB_TOKEN` | Token automático de Actions — permite que Sonar comente en los PRs de GitHub |
| `SONAR_TOKEN` | Autentica Maven contra SonarCloud (definido como Secret) |
| `-Dsonar.projectKey` | Identifica a qué proyecto de SonarCloud enviar los resultados |
| `-Dsonar.organization` | Identifica tu organización dentro de SonarCloud |
| `-Dsonar.host.url` | URL del servidor SonarCloud (siempre este valor para la versión cloud) |
| `-Dsonar.coverage.jacoco.xmlReportPaths` | Ruta al reporte XML de JaCoCo generado en el paso anterior |

> 💡 `GITHUB_TOKEN` es un secret especial que GitHub Actions genera automáticamente
> en cada ejecución. **No necesitas crearlo** en la configuración de Secrets.

---

### Cambio 4 — Nuevo Paso 6: Snyk en dependencias Maven

```yaml
- name: Escaneo de vulnerabilidades Snyk (dependencias)
  uses: snyk/actions/maven@master
  continue-on-error: true
  env:
    SNYK_TOKEN: ${{ secrets.SNYK_TOKEN }}
  with:
    args: --severity-threshold=high
```

**Explicación:**

| Elemento | ¿Qué hace? |
|---|---|
| `snyk/actions/maven@master` | Action oficial de Snyk para proyectos Maven — lee el `pom.xml` |
| `continue-on-error: true` | El pipeline continúa aunque Snyk encuentre vulnerabilidades |
| `SNYK_TOKEN` | Autentica Snyk con tu cuenta para acceder a la base de datos de CVEs |
| `--severity-threshold=high` | Solo reporta vulnerabilidades de severidad **High** y **Critical** |

> ⚠️ En un entorno de producción real se eliminaría `continue-on-error: true`
> para que el pipeline **falle y bloquee** el deploy si hay vulnerabilidades críticas.

---

### Cambio 5 — Nuevo Paso 10: Snyk en imagen Docker

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

**¿Por qué escanear la imagen Docker además del código?**

El `Dockerfile` usa `eclipse-temurin:17-jre` como imagen base.
Esta imagen base incluye el sistema operativo Linux con cientos de paquetes instalados.
Algunos de esos paquetes pueden tener vulnerabilidades conocidas aunque tu código sea perfecto.
Snyk escanea esas capas de la imagen y reporta los CVEs encontrados.

**Orden importante:**
Este paso va **después** del `docker build` (paso 9) y **antes** del `docker push` (paso 11).
Así se escanea la imagen antes de publicarla en DockerHub.

---

## 🚀 Paso 8 — Ejecuta el pipeline completo

Una vez que tienes todos los secrets configurados y ambos archivos actualizados
(`pom.xml` y `.github/workflows/main.yml`), es momento de hacer push.

### Verifica el estado local antes de hacer push

```bash
# Confirma que estás en la rama correcta
git branch
# Debe mostrar: * main

# Confirma que tienes commits previos (el historial importa para SonarCloud)
git log --oneline -5

# Ejecuta los tests localmente para verificar que todo pasa
cd mysql-local-2.4
mvn test
# Todos los tests deben pasar: BUILD SUCCESS
```

### Revisa los cambios antes de confirmarlos

```bash
# Ve los archivos que cambiaron
git status

# Ve exactamente qué cambió en cada archivo
git diff pom.xml
git diff .github/workflows/main.yml
```

### Haz commit y push

```bash
# Agrega los dos archivos modificados al staging
git add pom.xml .github/workflows/main.yml

# Confirma los cambios con un mensaje descriptivo
git commit -m "feat: integra SonarCloud y Snyk en el pipeline CI"

# Publica los cambios al repositorio remoto
git push origin main
```

### Observa la ejecución del pipeline

1. Ve a tu repositorio en GitHub
2. Haz click en la pestaña **"Actions"**
3. Verás un nuevo workflow ejecutándose llamado **"CI — Tests, Calidad y Seguridad"**
4. Haz click en él para ver el detalle en tiempo real

La ejecución completa tiene esta secuencia esperada:

```text
✅  Checkout del repositorio          ~5s   → descarga el historial completo
✅  Configurar Java 17                ~15s  → instala JDK + restaura caché Maven
✅  Ejecutar tests                    ~60s  → tests + genera jacoco.xml
✅  Subir reporte JaCoCo              ~5s   → artefacto "jacoco-report" disponible
✅  Análisis SonarCloud               ~90s  → resultados en sonarcloud.io
✅  Escaneo Snyk (dependencias)       ~30s  → resultados en snyk.io
✅  Build del proyecto                ~30s  → genera el .jar
✅  Login en DockerHub                ~5s   → autenticación
✅  Build de la imagen Docker         ~120s → construye la imagen
✅  Escaneo Snyk (imagen Docker)      ~45s  → resultados en snyk.io
✅  Push de la imagen a DockerHub     ~30s  → publica la imagen
```

> ⏱️ **El pipeline completo tarda ~7-10 minutos** la primera vez (descarga de dependencias).
> Las siguientes ejecuciones son más rápidas gracias al caché de Maven configurado
> en `cache: maven` del paso de Java.

---

## 📊 Paso 9 — Interpreta los resultados en SonarCloud

### Accede al dashboard

1. Ve a **https://sonarcloud.io**
2. Haz click en tu proyecto `bdget` en la lista de proyectos
3. Verás el **dashboard principal** con el resumen de calidad:

```text
┌─────────────────────────────────────────────────────────────┐
│  Quality Gate: ✅ PASSED  (o ❌ FAILED)                       │
├──────────┬──────────┬──────────┬──────────┬─────────────────┤
│  Bugs    │  Vulner. │  Smells  │ Coverage │  Duplication    │
│   0  A   │   0  A   │   X  B   │  XX.X%   │    X.X%         │
└──────────┴──────────┴──────────┴──────────┴─────────────────┘
```

### ¿Qué es el Quality Gate?

El Quality Gate es el semáforo de calidad del proyecto. El Quality Gate por defecto de
SonarCloud (**"Sonar way"**) aplica estas condiciones solo al **código nuevo**:

| Condición | Umbral | ¿Qué pasa si falla? |
|---|---|---|
| Bugs nuevos | 0 | Quality Gate en ❌ FAILED |
| Vulnerabilidades nuevas | 0 | Quality Gate en ❌ FAILED |
| Cobertura en código nuevo | ≥ 80% | Quality Gate en ❌ FAILED |
| Duplicación en código nuevo | < 3% | Quality Gate en ❌ FAILED |

> 💡 Un Quality Gate en FAILED no detiene el pipeline en esta configuración
> (no bloqueamos en el YAML), pero es una señal de que el código tiene problemas.

### Navega por el reporte

- **"Issues"** → lista todos los code smells, bugs y vulnerabilidades con el archivo y línea exacta
- **"Coverage"** → porcentaje de líneas cubiertas por tests, mapeado sobre el código
- **"Security Hotspots"** → puntos del código que merecen revisión de seguridad manual
- **"Code"** → explorador del código fuente con los issues marcados visualmente

> 📸 **Toma capturas de:**
> - El dashboard con el Quality Gate y las métricas
> - La sección "Issues" (aunque esté vacía: `0 issues`)

---

## 🔎 Paso 10 — Interpreta los resultados en Snyk

### Resultados en GitHub Actions

En la pestaña **Actions** de GitHub, expande el paso **"Escaneo de vulnerabilidades Snyk (dependencias)"**:

```text
Testing /github/workspace...

✗ High severity vulnerability found in com.some.library:name
  Description: Improper Input Validation [CVE-2023-XXXXX]
  Info: https://security.snyk.io/vuln/SNYK-JAVA-...
  Introduced through: com.some.library:name@X.X.X
  Fixed in: X.Y.Z

Tested 42 dependencies for known issues, found N issues.
```

Si no hay vulnerabilidades High/Critical:
```text
Testing /github/workspace...
✓ Tested 42 dependencies for known issues, no vulnerable paths found.
```

### Resultados en el dashboard de Snyk

1. Ve a **https://snyk.io** → haz click en **"Projects"** (menú lateral)
2. Busca tu repositorio `bdget`
3. Haz click en el proyecto para ver el detalle de cada CVE encontrado

| Campo | Significado |
|---|---|
| **Severity** | Critical / High / Medium / Low |
| **CVE** | Identificador oficial (ej: CVE-2023-XXXXX) |
| **Introduced through** | Librería de tu `pom.xml` que introduce el problema |
| **Fixed in** | Versión de la librería donde fue corregido (si existe) |
| **Is upgradeable** | Si Snyk puede aplicar el fix automáticamente |

> 📸 **Toma capturas de:**
> - El paso de Snyk en GitHub Actions (dependencias) — mostrando el resultado del escaneo
> - El paso de Snyk en GitHub Actions (imagen Docker) — mostrando el resultado del escaneo

---

## 🛑 Paso 11 — Solución de problemas frecuentes

### ❌ SonarCloud falla con "Project not found"

**Síntoma:**
```text
ERROR: Error during SonarScanner execution
Project 'TU-USUARIO_bdget' can't be found on https://sonarcloud.io
```

**Solución:**
1. Ve a SonarCloud → tu proyecto → verifica el valor exacto del Project Key
2. Verifica que el Secret `SONAR_PROJECT_KEY` en GitHub coincide exactamente (sensible a mayúsculas)
3. Verifica que el proyecto fue creado correctamente en SonarCloud

```bash
# Si no recuerdas el project key, búscalo en la URL de SonarCloud:
# https://sonarcloud.io/project/overview?id=TU-USUARIO_bdget
#                                                    ^^^^^^^^^^^^ este es tu project key
```

---

### ❌ SonarCloud falla con "You must define the following mandatory properties"

**Síntoma:**
```text
ERROR: You must define the following mandatory properties for 'Unknown': sonar.projectKey
```

**Solución:**
Verifica que el Secret `SONAR_PROJECT_KEY` está configurado en GitHub
(Settings → Secrets → Actions) y que el nombre es exactamente `SONAR_PROJECT_KEY`.

---

### ❌ SonarCloud falla con análisis incorrecto / "fetch-depth"

**Síntoma:** El análisis funciona pero las métricas de código nuevo están vacías o incorrectas.

**Solución:**
Confirma que el checkout tiene `fetch-depth: 0`:
```yaml
- uses: actions/checkout@v4
  with:
    fetch-depth: 0    # ← esta línea debe estar presente
```

---

### ❌ Snyk falla con "Missing SNYK_TOKEN"

**Síntoma:**
```text
Error: SNYK_TOKEN environment variable is not set
```

**Solución:**
1. Verifica que creaste el Secret `SNYK_TOKEN` en GitHub
2. Verifica que el nombre en el YAML es exactamente `SNYK_TOKEN` (mayúsculas, sin espacios)
3. Si lo acabas de crear, intenta re-ejecutar el pipeline desde Actions → botón "Re-run jobs"

---

### ❌ Snyk reporta muchas vulnerabilidades en la imagen Docker

**Síntoma:** El paso de escaneo Docker muestra decenas de vulnerabilidades.

**Explicación:** La imagen base `eclipse-temurin:17-jre` incluye paquetes del SO Linux
con CVEs conocidos. Esto es normal para cualquier imagen base.

**Solución a largo plazo:**
```dockerfile
# Cambiar de la imagen estándar a la versión Alpine (mucho menos paquetes)
FROM eclipse-temurin:17-jre-alpine
```

La imagen Alpine tiene ~80% menos paquetes del SO, por lo que reduce drásticamente
la superficie de ataque.

---

### ❌ Quality Gate de SonarCloud falla por cobertura baja

**Síntoma:** Quality Gate en ❌ FAILED con mensaje "Coverage on new code is less than 80%"

**Solución:**
```bash
# 1. Ejecuta los tests localmente y revisa el reporte HTML
mvn test
open target/site/jacoco/index.html    # macOS
# o en Windows: start target/site/jacoco/index.html

# 2. Identifica las clases con menos de 80% de cobertura
# 3. Agrega tests para las líneas no cubiertas
# 4. Vuelve a hacer push para re-ejecutar el pipeline
```

---

## 📊 Paso 12 — Resumen de comandos útiles

| Acción | Comando |
|---|---|
| Verificar versión de Java | `java -version` |
| Verificar versión de Maven | `mvn -version` |
| Ejecutar tests localmente | `mvn test` |
| Limpiar y recompilar todo | `mvn clean test` |
| Ejecutar análisis Sonar local | `mvn sonar:sonar -Dsonar.token=TU_TOKEN` |
| Instalar Snyk CLI | `npm install -g snyk` |
| Autenticar Snyk CLI | `snyk auth` |
| Escanear dependencias con Snyk CLI | `snyk test --all-projects` |
| Escanear imagen Docker con Snyk CLI | `snyk container test usuario/bdget-app:latest` |
| Ver reporte JaCoCo (macOS) | `open target/site/jacoco/index.html` |
| Ver estado git local | `git status` |
| Ver historial de commits | `git log --oneline -5` |
| Agregar archivos modificados | `git add pom.xml .github/workflows/main.yml` |
| Hacer commit | `git commit -m "feat: integra SonarCloud y Snyk en el pipeline CI"` |
| Publicar cambios | `git push origin main` |

---

## ✅ Lista de verificación — Entregables de la Actividad 2.4

Antes de entregar, verifica que tienes evidencia (captura de pantalla) de cada punto.

### Configuración
- [ ] **Cuenta SonarCloud creada** y vinculada con GitHub
- [ ] **Cuenta Snyk creada** y vinculada con GitHub
- [ ] **6 Secrets configurados** en GitHub → Settings → Secrets → Actions:
  `SONAR_TOKEN`, `SONAR_PROJECT_KEY`, `SONAR_ORGANIZATION`, `SNYK_TOKEN`, `DOCKERHUB_USERNAME`, `DOCKERHUB_TOKEN`
- [ ] **`pom.xml` modificado** con las propiedades `sonar.projectKey`, `sonar.organization` y `sonar.host.url`
- [ ] **`.github/workflows/main.yml` actualizado** con los 11 pasos del nuevo pipeline

### Pipeline
- [ ] **Captura del pipeline en GitHub Actions** con **todos los pasos en verde** ✅
  (debe mostrar los 11 pasos: incluyendo "Análisis SonarCloud", "Escaneo Snyk dependencias" y "Escaneo Snyk imagen Docker")
- [ ] **Captura de la sección Artifacts** en GitHub Actions con el artefacto `jacoco-report` disponible para descarga

### SonarCloud
- [ ] **Captura del dashboard de SonarCloud** mostrando:
  - El Quality Gate (PASSED o FAILED)
  - Las métricas: Bugs, Vulnerabilities, Code Smells, Coverage, Duplication
- [ ] **Captura de la sección "Issues"** en SonarCloud (aunque muestre `0 open issues`)

### Snyk
- [ ] **Captura del paso "Escaneo Snyk (dependencias)"** en GitHub Actions
  mostrando el resultado del escaneo del `pom.xml` (con o sin vulnerabilidades)
- [ ] **Captura del paso "Escaneo Snyk (imagen Docker)"** en GitHub Actions
  mostrando el resultado del escaneo de la imagen `bdget-app:latest`

### Configuración de GitHub
- [ ] **Captura de la página de Secrets** en GitHub (Settings → Secrets → Actions)
  mostrando los 6 secrets configurados — **sin mostrar los valores**, solo los nombres

---

> **Total esperado: 10 capturas de pantalla** que demuestran la implementación completa.

---

*Guía del Estudiante — Asignatura DevOps | Actividad 2.4*
*Proyecto: `mysql-local-2.4` | Spring Boot + MySQL + GitHub Actions + SonarCloud + Snyk*
