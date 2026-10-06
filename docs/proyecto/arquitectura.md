# Arquitectura

> Estado: **propuesta**, implementada solo hasta el andamio (0.1.0). Lo que todavía no se
> construyó está marcado como tal.

## Qué es

Un gateway HTTP. Recibe preguntas sobre bonificaciones en **nuestro** contrato, las traduce a
una o varias llamadas al ERP de la distribuidora, y devuelve una respuesta normalizada con su
traza.

```
  entorno Axum                api-bonificaciones                      ERP (GESCOM)
 (tienda virtual  --->  /v1/{tenant}/...  ---> [ conector ] ---> Keycloak (token 5 min)
  o app de Axum)        [ REST + x-api-key ]        |         --> ventas/get-promociones
                                 |                  |         --> ventas/eval-pedido
                                 |                  |         --> ventas/get-clientes
                                 v                  v         --> inventario/get-articulos
                        modelo normalizado        cache
                          (bonif-core)          (Caffeine)
```

## Dónde encaja en el entorno Axum

El consumidor es **el entorno de Axum**: la tienda virtual o alguna app de la suite (decidido el
2026-10-06).

Eso ubica al gateway en un lugar preciso, y encaja sin pisar nada. MotorFiscal (`api-impuestos`)
declara explícitamente fuera de alcance *"el pricing comercial (listas de precios, descuentos,
recargo por cuotas, CFT) es de la tienda. El motor recibe una base ya neteada y devuelve
tributos"*. **Eso que la tienda tiene que resolver sola es exactamente lo que hace este
gateway.** La cadena en un checkout queda:

```
  tienda arma el carrito
        |
        v
  api-bonificaciones  -->  neto con descuento comercial (quién lo otorgó y por qué)
        |
        v
  MotorFiscal         -->  IVA, percepciones y total sobre esa base ya neteada
```

Los dos servicios se componen y no se superponen: uno responde *cuánto cuesta*, el otro *cuánto
se tributa*. Y los dos los llama el mismo tercer sistema, con el mismo patrón.

### Convenciones que se adoptan de Axum (no se inventan de nuevo)

De `C:\Dev\docs\axum\integracion-axum.md` y `axum-referencias.md`:

- **El tenant va en la ruta**: `/{tenant}/api/v1/…` en el gateway de Axum,
  `/v1/{tenant}/calculos` en MotorFiscal. Acá: `/v1/{tenant}/criterios`. Nuestro "tenant" **es**
  la distribuidora; se usa esa palabra y no una propia.
- **Auth por header `x-api-key`**, igual que el gateway de Axum. El equipo ya sabe integrarlo y
  no necesita infraestructura nueva.
- **Los nombres de campo de la respuesta se eligen para mapear 1:1 con lo que el consumidor ya
  usa**, no para ser lindos. MotorFiscal copió los nombres de `facturasimpagas` justamente por
  eso. Acá falta saber qué nombres usa la tienda en su línea de carrito (ver
  `informacion-que-falta.md`).

### Errores de Axum que no se repiten

Están listados en `axum-referencias.md` como "lo que cuesta caro". Los que nos tocan:

- **Un solo formato de fecha, ISO 8601 con zona**, en toda la API. Axum tiene dos formatos
  distintos en endpoints distintos.
- **Los enums se definen una vez y se validan en el borde**, con mayúsculas consistentes.
- **Las listas de valores son objetos con código y etiqueta separados**, nunca un string con el
  código metido adentro del texto.
- **La vigencia la filtra el endpoint por defecto.** Un criterio vencido no viaja en el payload
  para que el cliente lo descarte: eso es trabajo que el consumidor no debería tener que saber
  hacer. Quien quiera los vencidos los pide explícitamente.

## Módulos

| Módulo | Responsabilidad | Reglas |
|---|---|---|
| `bonif-core` | El modelo normalizado (`Criterio`, `Condicion`, `Modificador`, `Vigencia`) y los puertos (`CatalogoDeCriterios`, y después el de valorización). | **Sin Spring, sin HTTP, sin JSON.** Solo JDK. Se testea sin levantar nada. |
| `bonif-app` | La API REST, la configuración por distribuidora, el cache y **un conector por ERP**. | Todas las rarezas del ERP mueren acá. Nada de `CodigoItem` ni `configuracionJson` cruza hacia afuera. |

Es la misma división que `api-impuestos` (`fiscal-core` / `fiscal-app`) y por el mismo motivo: el
dominio tiene que ser testeable sin levantar el framework.

## La decisión más cara: delegar el cálculo en el ERP

**Decisión**: el gateway **no calcula descuentos**. Para saber qué descuento le cae a un pedido,
llama a `eval-pedido` y traduce la respuesta.

**Por qué**: la lógica de criterios de GESCOM (condiciones combinables `All`/`Any`, `inverted`,
`requiredQuantity`, `allowOverlap`, modificadores apuntando a condiciones por código) está
reverse-engineereada, no documentada. Reimplementarla significa que el día que no coincida con el
ERP, el preventista ve un precio y el pedido entra con otro. Un gateway que miente sobre el precio
es peor que no tener gateway.

**Qué nos cuesta**: una llamada al ERP por valorización (más el token), y que no podemos responder
si el ERP está caído. Es el precio de que el número sea el correcto.

**Alternativa descartada**: bajarse los criterios y evaluarlos localmente. Se descarta **por
ahora**; se reabre solo si aparece un caso de simulación masiva ("¿qué pasa si cambio esta
promo?") que `eval-pedido` no banque por performance. Si se reabre, el camino es
*shadow mode*: calcular local, llamar igual al ERP, y comparar — nunca reemplazar directo.

## Autenticación contra el ERP (pendiente de implementar — Fase 1)

Keycloak, grant `password`, **token de 5 minutos**. El gateway guarda las credenciales de cada
distribuidora y mintea el token él mismo, cacheado por distribuidora con margen (refresco a los
~4 min). No hay alternativa realista: un token relayado por el consumidor llega vencido.

**Consecuencia directa**: el gateway es un almacén de credenciales de varias distribuidoras. Eso
lo convierte en un objetivo, y obliga a que **tenga su propia autenticación antes de exponerlo**
(ver `decisiones-abiertas.md`, decisión 2).

## Manejo de errores

El ERP colapsa casi todo a `{"errorCode":"0","message":"Error desconocido"}`. Traducir eso a algo
accionable es buena parte del valor del gateway. El conector clasifica, como mínimo:

| Situación en el ERP | Qué devuelve el gateway |
|---|---|
| `Unity.Exceptions.InvalidRegistrationException` | el comando no existe — error nuestro, 500, log fuerte |
| 401 / token rechazado | credenciales de la distribuidora inválidas o vencidas |
| `errorCode: 0` genérico | error del ERP, no clasificable: se devuelve con el crudo adjunto |
| timeout / conexión | ERP no disponible — 503, reintentable |
| distribuidora no configurada | 400 con código de dominio, antes de tocar la red |

## Cache (pendiente — Fases 1 y 3)

Caffeine en memoria, un solo proceso. Tres cosas distintas con TTL distinto:

- **token** por distribuidora: ~4 min (vive 5).
- **criterios** (`get-promociones`): minutos. Cambian cuando alguien los edita en GESCOM.
- **catálogos** (clientes, artículos): más largo. Son maestros, se mueven poco.

Nada de esto es cache de resultados de valorización: el precio no se cachea.

## Tests

- `bonif-core`: JUnit puro.
- conectores: **WireMock**, un servidor HTTP de verdad con respuestas reales capturadas del ERP.
  Un mock en proceso no atrapa los bugs que importan acá (headers, formato del body, el `Pedido`
  envuelto, `CodigoItem` vs `CodigoArticulo`).
- contra el ERP real: tests etiquetados `erp`, excluidos del build por defecto, se corren con
  `-PincludeErpTests` y credenciales cargadas. Son los que prueban que lo reverse-engineereado
  sigue siendo cierto.
