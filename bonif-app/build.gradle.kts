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
    // El spec OpenAPI se genera del codigo (controllers + records), no se mantiene a mano: la
    // leccion de api-impuestos es que un documento aparte se desactualiza sin que nadie lo note.
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.5")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Los conectores se testean contra un servidor HTTP de verdad, no contra un mock en proceso:
    // los bugs de integracion viven en el ensamblado (headers, formato del body, errores raros).
    testImplementation("org.wiremock:wiremock-standalone:3.10.0")
}

/**
 * Reemplaza @version@ en application.yml al empaquetar, para que /health informe la version que
 * realmente esta corriendo. Sin esto habria que acordarse de actualizarla a mano y terminaria
 * mintiendo, que es peor que no tenerla.
 */
tasks.named<ProcessResources>("processResources") {
    // Gradle no rastrea el valor de un filter() como input de la tarea: sin esta linea, un bump de
    // version sin tocar el archivo deja la tarea "up to date" y el jar queda con la version VIEJA
    // embebida. En api-impuestos eso se comio un release entero.
    inputs.property("version", project.version)
    // ReplaceTokens y no expand(): expand usa plantillas Groovy y romperia con los ${PORT:8080} del
    // propio yml, que son placeholders de Spring y no del build.
    filesMatching("application.yml") {
        filter<org.apache.tools.ant.filters.ReplaceTokens>(
            "tokens" to mapOf("version" to project.version.toString()))
    }
}
