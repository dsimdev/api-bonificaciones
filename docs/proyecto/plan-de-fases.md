# Plan de desarrollo

Cada fase cierra con un release y tiene un **criterio de salida verificable**. No se arranca la
siguiente hasta que la anterior lo cumple.

**Consumidor decidido el 2026-10-06: el entorno de Axum** (la tienda virtual o alguna app de la
suite). Y **lo más urgente a integrar es `eval-pedido`** (decidido el 2026-10-06): es la llamada
del checkout, y además no depende del catálogo, así que va primero y el catálogo baja a la Fase 2
como enriquecimiento.

Una consecuencia que no se posterga: la **autenticación** deja de ser "para más adelante". En
cuanto la tienda lo consuma por red, el gateway queda expuesto con credenciales de todas las
distribuidoras adentro. Si eso pasa antes de la Fase 5, el `x-api-key` se adelanta.

---

## Fase 0 — Andamio · v0.1.0 · **HECHA**

Gradle multi-módulo, Java 21, Spring Boot 3.4, `/health`, OpenAPI generado del código,
configuración por distribuidora con credenciales por variable de entorno, kit de método
instalado.

**Criterio de salida (cumplido)**: `gradlew build` verde, la app levanta, `/health` informa la
versión real embebida por el build y las distribuidoras configuradas.

---

## Fase 1 — Valorizar con `eval-pedido`, con catálogo · v0.2.0 · **✅ CERRADA**

`POST /v1/{tenant}/valorizaciones` sobre GESCOM. Es la llamada del checkout y lo que paga el
proyecto.

`eval-pedido` ya devuelve `promoId` y `promoNombre`, así que el número no depende del catálogo —
pero el catálogo entró igual en esta fase (decisión del usuario) para poder devolver **las
condiciones que dispararon** cada descuento. Detalle de `get-promociones` en
[fuente-gescom-criterios.md](fuente-gescom-criterios.md).

1. `ServicioDeToken` — Keycloak grant `password` por distribuidora, cache Caffeine con refresco a
   los ~4 min. **Nunca loguea el token.**
2. `ConectorGescom` — `RestClient` sobre `https://<distri>.gescom.online/data/cmd/`, con timeouts
   explícitos y la clasificación de errores de `arquitectura.md`.
3. `POST /v1/{tenant}/valorizaciones` — contrato propio (cliente + ítems). Por dentro arma el
   `Pedido` envuelto con `CodigoItem` (**no** `CodigoArticulo`) y un `Identificador` GUID
   **generado por nosotros** — idempotencia, la lección del `operationGuid` de Axum.
4. Validación que falle **antes** de gastar la llamada: ítems vacíos, cantidad ≤ 0, tenant
   desconocido. Con códigos de dominio.
5. Respuesta normalizada por línea: neto, neto con descuento, descuento, y el detalle de qué
   promo lo otorgó. **Siempre dice que el número lo dio el ERP.**
6. `CatalogoGescom` — `get-promociones` normalizado y cacheado, para enriquecer cada descuento con
   las condiciones que lo dispararon. **Si falla, la valorización igual responde**: el catálogo
   explica el número, no lo produce.

**Criterio de salida — ✅ CUMPLIDO para dyssa el 2026-10-07**: `ValorizacionContraGescomRealIT`
reproduce el caso verificado (cliente 8380 / ítem 5000014792 / lista 2 → `58424.22` →
`52581.798`, 10%) **pasando por nuestro endpoint**, contra la API en vivo. Y
`CatalogoContraGescomRealIT` confirma que el parseo del catálogo real no pierde nada: ningún tipo
de condición ni de modificador sin mapear, ninguna condición conocida sin valores.

**Y cerrado del todo con senderolaser el 2026-10-07**: los dos casos verificados pasan en vivo, el
informe de parseo corre contra las dos distribuidoras, y
`cadaDistribuidoraRecibeSuPropioCatalogoYNoElDeLaOtra` prueba el **aislamiento entre tenants** del
cache de tokens — que era el motivo real de necesitar una segunda distribuidora.

51 tests verdes, ninguno salteado. Correr los que pegan al ERP: `gradlew cleanTest test
-PincludeErpTests` con el `.env` cargado (hace falta `cleanTest`: si no, Gradle los da por
up-to-date y no los vuelve a correr).

---


## Fase 2 — Explicar por qué · v0.3.0

Lo que GESCOM **no** da y es la pregunta real del negocio: *"¿por qué este cliente no tiene esta
promo?"*.

1. Catálogos cacheados: `get-clientes` (ventas) y `get-articulos` (**inventario**, no ventas).
2. `GET /v1/{tenant}/clientes/{codigo}/criterios` — qué criterios podrían aplicarle a ese
   cliente, cruzando sus `tags` / `codigoSubramo` / `codigo` contra las condiciones.
3. Diagnóstico por criterio: qué condición se cumple y cuál no.

> Ojo: esto **sí** es evaluar criterios de nuestro lado, y contradice en parte la regla de
> delegar. La salida es que el resultado se marque explícitamente como **informativo**, nunca
> como precio. El número sigue saliendo solo de `eval-pedido`.

**Criterio de salida**: para un criterio real con condiciones combinadas (`All`/`Any`), el
diagnóstico coincide con lo que `eval-pedido` efectivamente aplica en un pedido de prueba.

---


---

## Fase 3 — Multi-tenant, auth y panel · v0.4.0 … v0.6.0

La fase más grande del proyecto. Es lo que convierte esto de "anda en una máquina con dos
distribuidoras" a "lo operan mil". Detalle en [multi-tenant-y-auth.md](multi-tenant-y-auth.md).

**Decidido el 2026-10-07: van la API de administración Y el panel.** No es o uno o el otro: la
API es lo que permite el alta masiva, y el panel es lo que permite que lo opere gente que no
trabaja con APIs. Los dos tienen usuarios distintos.

### 3a — La base · v0.4.0

Sin esto no hay nada más: las variables de entorno no escalan a 1000.

1. **SQL Server + Flyway.** Tablas de distribuidora y de credenciales. El servidor ya lo tiene y
   `api-impuestos` ya lo usa.
2. **Secretos cifrados en reposo** (`CifradoDeSecretos` de MotorFiscal es el molde). Guardamos las
   claves de API de mil distribuidoras: en texto plano no van.
3. **API de administración** con clave maestra: alta, listar, cargar credenciales, regenerar la
   api-key de la tienda.

   **La validación en el acto no es un lujo: es el requisito central.** El alta la va a hacer
   alguien sin contexto, copiando de Postman, hasta 10 veces en un día. Si una clave se copia mal
   y nadie valida, queda una distribuidora rota que nadie descubre hasta que falla el checkout de
   esa tienda. El alta tiene que: mintear el token contra Keycloak, **traer el catálogo**, y
   responder "anda, trajo 70 criterios" o un error que se entienda sin saber de APIs
   ("la clave es incorrecta", no "401").
4. **Sin alta en lote.** Decidido el 2026-10-07: las credenciales están en Postman, una colección
   por distribuidora, y el usuario prefiere el alta manual de a una. No hay fuente de la que
   importar, y 5-10 altas en un día son 15 minutos a mano. **La API queda igual**, así que si algún
   día aparece una planilla, el import es barato de agregar.

**Criterio de salida**: dar de alta una distribuidora por API, que la validación rechace una
credencial mala, y que `/valorizaciones` funcione con la credencial que quedó en la base.

### 3b — Auth de la API pública · v0.5.0

1. `x-api-key` por tenant, **validando que la credencial sea del tenant de la ruta**. Sin eso, una
   tienda puede pedir los datos de otra distribuidora cambiando la URL.
2. **Alcances**: la clave del checkout **solo valoriza**. `/criterios` es back-office — es la
   estructura comercial completa y no va en un navegador.
3. Limitador de intentos (fuerza bruta) y cuota por credencial (que el bug de integración de una
   tienda no se coma la capacidad del resto).

**Criterio de salida**: con la clave de dyssa, `/v1/senderolaser/...` devuelve 403. Y con la clave
del checkout, `/criterios` devuelve 403.

### 3c — El panel · v0.6.0

Para los compañeros que no trabajan con APIs.

> **Prioridad corregida el 2026-10-07.** Yo había puesto el ABM último, con el argumento de que un
> alta se hace una vez y la pantalla de soporte se usa todas las semanas. **Estaba mal para este
> caso**: pueden salir 5-10 tiendas el mismo día y **tiene que poder hacerlo alguien que no sea el
> usuario**. El alta es el requisito operativo, no un trámite.

| Pantalla | Para qué | Prioridad |
|---|---|---|
| **Alta de distribuidora, con validación en el acto** | 5-10 altas en un día, hechas por alguien sin contexto copiando de Postman. Si no valida, quedan tiendas rotas que nadie descubre | **1** |
| **Estado de las distribuidoras** — cuáles andan, reprobar la credencial con un botón | "¿El alta que hice ayer quedó bien?" y detectar una credencial vencida antes que un cliente | **2** |
| **Probar una valorización** — cliente + ítems, y ver el resultado con el porqué | *"El cliente dice que no le hizo el descuento"*. Hoy eso se contesta con curl | **3** |
| **Ver los criterios de una distribuidora** | Qué promos hay cargadas, cuáles vencen, a quién aplican. Ya existe el endpoint | **4** |

### Lo que el formulario de alta tiene que hacer bien

El origen de los datos es **una colección de Postman por distribuidora**. Quien da de alta abre
Postman, busca la colección y copia. Entonces:

- **Los campos se llaman como en Postman**: `DISTRIBUIDORA`, `USERNAME`, `PASSWORD`. Sin
  traducción, para que copiar sea copiar y no interpretar.
- **`host` y `realm` no se piden**: salen del código por convención. Menos para equivocarse.
- **Al guardar, se prueba de punta a punta**: mintea el token y **trae el catálogo**. El resultado
  es *"Listo: la credencial anda y trajo 70 criterios"*. Eso es un smoke test completo hecho por
  alguien que no sabe qué es un token.
- **Los errores en castellano, sin códigos HTTP**: "la clave es incorrecta", "esa distribuidora no
  existe en GESCOM", "GESCOM no responde".
- **La api-key de la tienda se muestra una sola vez**, con la advertencia de guardarla ahora —
  igual que hace MotorFiscal al regenerar.

**Tecnología: se copia la de MotorFiscal**, no se inventa. Export estático de Next.js embebido en
el jar, servido en `/admin` — mismo origen que la API, **sin CORS ni mixed content**, un solo
artefacto para deployar.

> ⚠️ **El panel arrastra una trampa conocida.** Si va detrás de un IIS que lo cuelga como
> aplicación anidada, hay que compilarlo con el prefijo de ruta completo o queda en blanco con
> 404. En `api-impuestos` ese bug **llegó a producción tres veces** (el redirect de `/admin`,
> `swagger-ui.url` y `swagger-ui.config-url`). Toda URL que el panel arme para sí mismo sale de
> configuración, y se prueba **a través** del proxy, nunca contra `localhost:8080` directo. Desde
> que exista el panel, eso es parte del checklist de deploy.

### 3d — Endurecer y deployar · v0.6.x

1. Timeouts, reintentos acotados y qué devolver cuando el ERP está caído.
2. Métricas y logs útiles sin un solo secreto adentro.
3. Deploy: servicio de Windows, igual que `api-impuestos` (sin Docker en el entorno).
4. Colección Postman del gateway, para el integrador.

**Criterio de salida de la Fase 3**: corriendo en el servidor real, con auth, con una distribuidora
dada de alta desde el panel, y la tienda haciendo una valorización end-to-end.

---

## Fase 4 — Chess, si entra · v1.0.0

La única segunda fuente que queda en el horizonte. Hoy **no sabemos qué es Chess** — ni una
mención en `C:\Dev\docs`. Arranca con el método de reversing (`metodo-reversing-gescom-api`), igual
que se hizo con GESCOM, y escribiendo la referencia en `C:\Dev\docs` **antes** del código.

**Criterio de salida**: el mismo request del consumidor, cambiando solo el tenant, devuelve la
misma forma de respuesta contra otra fuente. **Hasta que eso no pase, "contrato único" es una
hipótesis, no un hecho** — y por eso no se inventan abstracciones multi-fuente antes de tiempo.

> El gateway de Axum **no** entra acá: quedó fuera de alcance el 2026-10-07 porque la tienda lo
> consume directo. Ver `fuente-axum-bonificaciones.md`.

---

## Lo que no está en el plan (y por qué)

| Cosa | Por qué no |
|---|---|
| Crear/confirmar pedidos en el ERP | Otro alcance, con riesgo de escritura. Necesita decisión explícita. |
| Alta y edición de criterios | Se hace en GESCOM. Duplicarlo es pelearse con el ERP. |
| Calcular descuentos localmente | Ver `arquitectura.md`: solo en shadow mode y si aparece el caso que lo justifique. |
| Panel web propio | No se pidió. Si hace falta, se evalúa como repo aparte o estático embebido, como el de `api-impuestos`. |
| Base de datos | No hace falta todavía. Ver decisión abierta #4. |
