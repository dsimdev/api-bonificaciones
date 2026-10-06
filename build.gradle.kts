plugins {
    // `base` y no `java-library`: el proyecto raiz no tiene codigo propio, solo agrupa bonif-core
    // y bonif-app. java-library ademas empaquetaria un jar vacio con el mismo nombre que el real
    // -- en api-impuestos eso ya causo que se deployara el jar equivocado.
    base
}

allprojects {
    group = "com.axum.bonificaciones"
    version = "0.1.0"
}

subprojects {
    apply(plugin = "java-library")

    repositories { mavenCentral() }

    extensions.configure<JavaPluginExtension> {
        toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
    }

    dependencies {
        "testImplementation"("org.junit.jupiter:junit-jupiter:5.11.4")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test>().configureEach {
        // Tests etiquetados "erp" pegan contra una API de ERP real (GESCOM) y necesitan
        // credenciales de una distribuidora: se excluyen por defecto para que `gradlew build`
        // siga verde en cualquier maquina y sin secretos. Correr con -PincludeErpTests.
        useJUnitPlatform {
            if (!project.hasProperty("includeErpTests")) {
                excludeTags("erp")
            }
        }
    }
}
