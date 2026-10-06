# Información que falta para avanzar

Lista concreta de lo que hace falta conseguir, ordenada por cuándo bloquea. Lo que no está acá,
ya lo tenemos.

> **Aclaración importante sobre las credenciales**: lo que caduca cada 5 minutos es el **token**,
> no el usuario y la clave. Lo que hace falta es el **usuario y la clave de API** de cada
> distribuidora (los de la colección Postman), que no vencen. El token lo mintea el gateway solo,
> en el mismo proceso que lo usa, cada vez que lo necesita. **Nadie tiene que estar generando
> tokens a mano.**

## Bloquea la Fase 1 (hablar con GESCOM)

| Qué | Dónde está / quién lo tiene | Para qué |
|---|---|---|
| **Usuario y clave de API de UNA distribuidora** — se recomienda `dyssa` | colección Postman de la distribuidora | Alcanza para construir y probar toda la Fase 1. Van al `.env` local, nunca al repo. **No vencen.** |
| **Una segunda distribuidora** (`senderolaser`) — *más adelante, no ahora* | ídem | **No hace falta para construir, sí para confiar.** Ver abajo. |

### Por qué en algún momento hacen falta dos (pero no para arrancar)

Con una sola distribuidora **no se puede distinguir** "esto funciona" de "esto funciona para la
configuración particular de dyssa". Tres cosas que una sola distri no prueba:

1. **El aislamiento entre tenants.** El cache de tokens está indexado por distribuidora. Un bug
   que le devuelva a la distribuidora B el token de la A es invisible con una sola, y es el peor
   bug posible acá: datos comerciales de una distribuidora servidos a otra.
2. **La cobertura del parser.** Cada distribuidora configura los tipos de condición que usa. Con
   una sola mapeamos lo que usa dyssa y el resto aparece en producción.
3. **Que el host y el realm estén bien parametrizados** y no haya nada de dyssa hardcodeado sin
   que nos demos cuenta.

**Recomendación**: arrancar con `dyssa` y sumar `senderolaser` antes de cerrar la Fase 1. Se
elige `dyssa` porque es la que tiene el caso más rico ya verificado: la promo "GRUPO 10", con
doble descuento en un mismo criterio (10% a marcas Pepsico con `dataConditionCodes:[102]` + 5% a
Pehuamar con `[103]`). Ese caso ejercita condiciones combinadas y varios modificadores a la vez,
que es justo donde el parser se puede romper en silencio.
| **Lista definitiva de distribuidoras** que van a entrar al gateway | negocio | Cada una es una entrada de configuración y un realm de Keycloak distinto. |
| **Respuesta real de `get-promociones`** de 2 distribuidoras (JSON completo, guardado a archivo) | se obtiene corriendo el script de token + curl | Son los fixtures de los tests con WireMock. Sin JSON reales, el parser se escribe adivinando. |

> El script para mintear el token y capturar respuestas es el mismo método ya validado
> (`metodo-reversing-gescom-api`): token y llamada **en el mismo proceso**, porque el token dura
> 5 minutos. Lo corre el usuario y pega la salida.

## Bloquea la Fase 2 (valorizar)

| Qué | Para qué |
|---|---|
| **Un pedido real** de cada distribuidora (JSON tal cual lo manda el sistema al ERP) | Confirmar que no se nos escapa ningún campo de `RealPedidoCreateDto`. Fue así como se descubrió que el ítem es `CodigoItem` y no `CodigoArticulo`. |
| Confirmación de si `CodigoListaPrecio` y `UnidadFactor` son **realmente** opcionales | La doc los marca como "probable opcional, no aislado al 100%". Se cierra probando, no asumiendo. |
| Qué campos del pedido necesita **el consumidor** mandar (¿vendedor? ¿condición de pago? ¿fecha?) | Define nuestro contrato de entrada. Hoy valorizamos con lo mínimo, pero un criterio podría depender de alguno de esos. |

## Bloquea la Fase 3 (explicar)

| Qué | Para qué |
|---|---|
| Respuesta real de `get-clientes` y de `get-articulos` (servicio `inventario`) | Los catálogos contra los que se cruzan las condiciones. |
| Volumen aproximado: cuántos clientes y artículos tiene una distribuidora típica | Decide si el catálogo se cachea entero en memoria o se consulta puntual. Cambia el diseño. |
| Si `get-promociones` y `get-clientes` **paginan**, y cómo | No está verificado. Si paginan y lo ignoramos, nos faltan datos en silencio. |

## Bloquea congelar el contrato (Fase 2)

| Qué | Para qué |
|---|---|
| **Cómo arma la tienda su línea de carrito** y qué campos le manda hoy a MotorFiscal | Para que nuestra respuesta mapee 1:1 con lo que la tienda ya usa, sin traducción en el medio. Es la lección del `Hallazgo 1` de `integracion-axum.md`. |
| Si la tienda ya aplica algún descuento por su cuenta (lista de precios, cuotas, CFT) | Para no duplicar el descuento. MotorFiscal declara el pricing comercial fuera de alcance, pero no dice quién lo resuelve hoy. |
| Quién emite y administra las `x-api-key` del entorno Axum | Para no inventar un esquema propio si ya hay uno. |

## Bloquea la Fase 4 (deploy)

| Qué | Para qué |
|---|---|
| Servidor destino, puerto, y si va detrás de IIS (y con qué ruta virtual) | Si hay IIS anidado, el prefijo de ruta se resuelve desde el día uno. En `api-impuestos` eso llegó a producción tres veces. |
| Quién va a consumir el gateway y desde dónde | Define la auth (decisión abierta #2) y si hace falta HTTPS propio o lo termina el IIS. |

## Para sumar el gateway de Axum como fuente

| Qué | Para qué |
|---|---|
| **Qué le vamos a pedir a Axum** | Su gateway **no tiene promociones ni descuentos** (verificado contra los shapes reales de `/clientes` y `/articulos`). Lo que sí tiene son atributos de cliente/artículo y listas. Hace falta decidir para qué lo queremos: ¿enriquecer la respuesta?, ¿resolver atributos sin pegarle a GESCOM?, ¿otra cosa? |
| `x-api-key` y nombre de tenant en Axum por distribuidora | Para configurar la fuente. |
| Si el `GET` de los endpoints que necesitamos está habilitado | Al 2026-08-19 varios devolvían `405 Allow: POST` (son de ingesta, no de consulta). `/percepciones` lo habilitaron después, así que la tabla puede estar vieja: hay que reprobarlo. |

## Para sumar Chess

| Qué | Para qué |
|---|---|
| **Qué es Chess** | No hay una sola mención en `C:\Dev\docs`. Hoy no sabemos ni qué tipo de sistema es ni qué aporta. |
| Colección Postman / cualquier doc o acceso | Es el punto de partida del reversing, igual que lo fue con GESCOM. |
| Qué distribuidoras lo usan | Define si el gateway tiene que convivir con conectores de varias fuentes a la vez. |

## Preguntas para el negocio (no técnicas)

1. ¿Qué pregunta tiene hoy el negocio que GESCOM **no** le contesta? Esa es la feature que
   justifica el proyecto; el resto es plomería.
2. ¿Quién sufre hoy el problema: el preventista, el supervisor, el que carga las promos?
3. ¿Hay algún caso donde el descuento que muestra el sistema **no coincide** con el que entra al
   ERP? Si existe, es el caso de prueba más valioso que podemos tener.
4. ¿Las promos se consultan sobre todo **antes** de armar el pedido (qué puedo ofrecer) o
   **durante** (cuánto queda este pedido)? Cambia qué endpoint importa primero.
