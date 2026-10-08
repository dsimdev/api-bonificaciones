plugins {
    id("org.springframework.boot") version "3.4.1"
    id("io.spring.dependency-management") version "1.1.7"
}

description = "Gateway REST: expone el modelo normalizado y habla con las APIs de los ERP."

dependencies {
    implementation(project(":bonif-core"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // Cache en memoria: el token de GESCOM dura 5 minutos y los catalogos (clientes, articulos)
    // cambian poco. Un solo proceso, nada distribuido hasta que haga falta.
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")
    // JDBC y no JPA, igual que api-impuestos: aca se leen filas de configuracion, no hay grafos
    // ni lazy loading que justifiquen un ORM, y el SQL se lee mejor escrito a mano.
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-sqlserver")
    runtimeOnly("com.microsoft.sqlserver:mssql-jdbc")
    // Solo el hashing de contrasenias (BCrypt), no el filter chain entero de Spring Security:
    // la autenticacion del panel es un login propio contra nuestra tabla de usuarios.
    implementation("org.springframework.security:spring-security-crypto")
    // El spec OpenAPI se genera del codigo (controllers + records), no se mantiene a mano: la
    // leccion de api-impuestos es que un documento aparte se desactualiza sin que nadie lo note.
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.5")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Los conectores se testean contra un servidor HTTP de verdad, no contra un mock en proceso:
    // los bugs de integracion viven en el ensamblado (headers, formato del body, errores raros).
    testImplementation("org.wiremock:wiremock-standalone:3.10.0")
}

// Donde IIS cuelga la app, visto desde el NAVEGADOR. Default '/admin' = acceso directo a Spring,
// sin proxy anidado, y entonces el prefijo externo es vacio. Detras de un IIS que la cuelgue
// anidada (-PpanelBasePath=/api/bonificaciones/admin) el prefijo es /api/bonificaciones.
//
// Declarado aca arriba porque lo usan DOS cosas: el panel (buildPanel, mas abajo) y Swagger, que
// tambien arma URLs absolutas para el navegador y por el mismo motivo se rompe detras del proxy.
val panelBasePath = (project.findProperty("panelBasePath") as String?) ?: "/admin"
val externalBasePath = panelBasePath.removeSuffix("/admin")

/**
 * Reemplaza @version@ y @externalBasePath@ en application.yml al empaquetar.
 *
 * La version, para que /health informe la que realmente esta corriendo: sin esto habria que
 * acordarse de actualizarla a mano y terminaria mintiendo, que es peor que no tenerla.
 *
 * El prefijo externo, para que Swagger arme bien sus propias URLs detras del proxy anidado. Esa
 * clase de bug llego a produccion tres veces en api-impuestos y dos de las tres fueron Swagger.
 */
tasks.named<ProcessResources>("processResources") {
    // Gradle no rastrea el valor de un filter() como input de la tarea: sin esta linea, un bump de
    // version sin tocar el archivo deja la tarea "up to date" y el jar queda con la version VIEJA
    // embebida. En api-impuestos eso se comio un release entero.
    inputs.property("version", project.version)
    // Mismo motivo que la linea de arriba: sin esto, cambiar -PpanelBasePath no invalida la tarea
    // y el yml queda con el prefijo del build anterior. Eso dejaria a Swagger apuntando a la ruta
    // equivocada en un jar que por fuera parece el correcto.
    inputs.property("externalBasePath", externalBasePath)
    // ReplaceTokens y no expand(): expand usa plantillas Groovy y romperia con los ${PORT:8081} del
    // propio yml, que son placeholders de Spring y no del build.
    filesMatching("application.yml") {
        filter<org.apache.tools.ant.filters.ReplaceTokens>(
            "tokens" to mapOf(
                "version" to project.version.toString(),
                "externalBasePath" to externalBasePath))
    }
}

/**
 * Publica el panel dentro del jar, en /admin.
 *
 * Es lo que hace que ande sin CORS ni mixed content: el panel queda en el MISMO origen que la API,
 * y se deploya una sola cosa, el jar. Copiado del molde de api-impuestos, incluidas sus lecciones.
 *
 * `gradlew build` RECONSTRUYE el panel, no solo copia lo que haya en panel/out: en api-impuestos
 * antes solo copiaba, asi que un cambio en el panel sin correr `npm run build` a mano quedaba
 * invisible -- el jar seguia sirviendo la version vieja aunque el build pasara y los tests dieran
 * verde.
 *
 * Si no hay `node_modules`, la tarea no falla: el gateway es util sin panel y `gradlew build` no
 * puede exigir Node instalado en cualquier maquina. Si `node_modules` SI existe, se asume que el
 * panel es parte del trabajo y un build roto del panel rompe el build -- visible es mejor que
 * silencioso.
 */
val panelSourceDir = layout.projectDirectory.dir("../panel")
val panelDir = panelSourceDir.dir("out")
val panelInstalado = panelSourceDir.dir("node_modules").asFile.exists()

// panelBasePath se declara arriba, junto a externalBasePath: lo necesitan tanto el panel como
// Swagger. Un deploy detras de un IIS que cuelgue esto como aplicacion anidada necesita compilar
// el panel con la ruta COMPLETA que ve el navegador -- ej.
// -PpanelBasePath=/api/bonificaciones/admin -- o el panel queda en blanco con 404 en la consola.
// Ver el comentario de panel/next.config.mjs: en api-impuestos ese bug llego a produccion TRES
// veces.

// El basePath queda horneado en el HTML/JS en tiempo de build: dos builds con distinto
// -PpanelBasePath son dos artefactos distintos, no el mismo jar en dos momentos. Sin el
// classifier, ambos se llaman igual y un build local posterior pisa en silencio el jar de
// produccion que ya se habia verificado -- paso dos veces en api-impuestos.
val esBuildDeProduccion = panelBasePath != "/admin"

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    if (esBuildDeProduccion) {
        archiveClassifier.set("prod")
        doLast {
            logger.lifecycle("Jar de PRODUCCION (panelBasePath=$panelBasePath): " +
                    archiveFile.get().asFile.name)
        }
    } else {
        doLast {
            logger.lifecycle("Jar LOCAL (panelBasePath=$panelBasePath): " +
                    archiveFile.get().asFile.name + " -- no usar para un deploy detras de proxy.")
        }
    }
}

val buildPanel by tasks.registering(Exec::class) {
    onlyIf {
        if (!panelInstalado) {
            logger.lifecycle("Panel: node_modules no existe, no se reconstruye " +
                    "(cd panel && npm install primero).")
        }
        panelInstalado
    }
    workingDir = panelSourceDir.asFile
    val npmCmd = if (System.getProperty("os.name").lowercase().contains("win")) "npm.cmd" else "npm"
    commandLine(npmCmd, "run", "build")
    environment("PANEL_BASE_PATH", panelBasePath)
    inputs.dir(panelSourceDir.dir("app"))
    inputs.dir(panelSourceDir.dir("lib"))
    inputs.file(panelSourceDir.file("next.config.mjs"))
    inputs.file(panelSourceDir.file("package.json"))
    inputs.file(panelSourceDir.file("tsconfig.json"))
    inputs.property("panelBasePath", panelBasePath)
    outputs.dir(panelDir)
}

// Sync y no Copy: un Copy nunca borra lo que ya estaba en el destino, asi que los chunks hasheados
// de builds anteriores se acumularian para siempre e hincharian el jar sin necesidad.
val copiarPanel by tasks.registering(Sync::class) {
    dependsOn(buildPanel)
    from(panelDir)
    into(layout.buildDirectory.dir("resources/main/static/admin"))
    onlyIf {
        val existe = panelDir.asFile.exists()
        if (!existe) {
            logger.lifecycle("Panel sin compilar (panel/out no existe). " +
                    "Para incluirlo: cd panel && npm install && npm run build")
        }
        existe
    }
}

tasks.named("classes") { dependsOn(copiarPanel) }
