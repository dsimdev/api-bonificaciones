# Información que falta para avanzar

Lista concreta de lo que hace falta conseguir, ordenada por cuándo bloquea. Lo que no está acá,
ya lo tenemos.

## Bloquea la Fase 1 (hablar con GESCOM)

| Qué | Dónde está / quién lo tiene | Para qué |
|---|---|---|
| **Credenciales de API por distribuidora** (usuario y clave del client `gcw-web-api`) de al menos `dyssa` y `senderolaser` | colección Postman de cada distribuidora | Sin esto el conector no se puede probar contra nada real. Van al `.env` local, nunca al repo. |
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

## Bloquea la Fase 4 (deploy)

| Qué | Para qué |
|---|---|
| Servidor destino, puerto, y si va detrás de IIS (y con qué ruta virtual) | Si hay IIS anidado, el prefijo de ruta se resuelve desde el día uno. En `api-impuestos` eso llegó a producción tres veces. |
| Quién va a consumir el gateway y desde dónde | Define la auth (decisión abierta #2) y si hace falta HTTPS propio o lo termina el IIS. |

## Para la Fase 5 (segundo ERP)

| Qué | Para qué |
|---|---|
| Colección Postman de SIGMA / GEWINN | Es el punto de partida del reversing, igual que lo fue con GESCOM. |
| Qué distribuidoras usan cada ERP | Define si el gateway tiene que convivir con dos conectores a la vez. |

## Preguntas para el negocio (no técnicas)

1. ¿Qué pregunta tiene hoy el negocio que GESCOM **no** le contesta? Esa es la feature que
   justifica el proyecto; el resto es plomería.
2. ¿Quién sufre hoy el problema: el preventista, el supervisor, el que carga las promos?
3. ¿Hay algún caso donde el descuento que muestra el sistema **no coincide** con el que entra al
   ERP? Si existe, es el caso de prueba más valioso que podemos tener.
4. ¿Las promos se consultan sobre todo **antes** de armar el pedido (qué puedo ofrecer) o
   **durante** (cuánto queda este pedido)? Cambia qué endpoint importa primero.
