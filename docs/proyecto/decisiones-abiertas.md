# Decisiones abiertas

Cada una con **recomendación**, no solo la pregunta. Cuando una se cierra, se borra de acá y se
anota en la memoria del proyecto (`MEMORY.md` → "Temas CERRADOS") con fecha y motivo.

---

## 0. ¿Qué pasa con un cliente que no existe en el ERP? — **bloquea el checkout de clientes nuevos**

### El problema

`eval-pedido` **exige** `CodigoCliente` y rechaza el pedido entero si no existe. Verificado en
vivo: devuelve `CLIENTE_INEXISTENTE` (400). O sea que para un cliente que no está en GESCOM **no
podemos devolver ni el descuento ni el precio**: los dos vienen de la misma llamada.

Y el caso existe, está documentado en `integracion-axum.md`:

> *"no todos los clientes de la tienda nueva van a venir por acá: los que llegan por fuera no
> tienen ERP que los alimente, así que **MotorFiscal tiene que funcionar también sin Axum**"*

MotorFiscal pudo resolverlo cayendo a defaults (consumidor final, alícuota general). **Nosotros no
podemos**: nuestra única fuente de verdad es el ERP, y el ERP se niega.

### La pregunta que lo desempata

**¿La tienda usa nuestro `neto`, o solo nuestro `descuento`?**

| Si la tienda… | Entonces un cliente nuevo… |
|---|---|
| ya tiene su precio (de Axum o su catálogo) y nos pide **solo el descuento** | es **trivial**: `CLIENTE_INEXISTENTE` ⇒ aplica 0% y muestra precio de lista. No hay nada que construir |
| usa **nuestro `neto`** como precio del carrito | **le rompe el checkout**. Hace falta una salida |

Hay que preguntárselo al dev. No es una decisión nuestra.

### Las salidas, si hiciera falta una

| Opción | Qué implica | Riesgo |
|---|---|---|
| **A. El cliente se da de alta en el ERP antes de comprar** | Nada de código. Es lo coherente con "el ERP manda", y en un mayorista B2B los clientes son empresas con cuenta, lista y condición de pago — no se dan de alta solos | La tienda no puede vender a alguien nuevo sin pasar por el ERP |
| **B. Cliente genérico ("mostrador") por distribuidora** | Un `clientePorDefecto` en la config del tenant + un `supuesto` en la respuesta diciendo que se valorizó contra el genérico | **El descuento sería el del genérico, no el del cliente real.** Cuando lo den de alta de verdad, el precio le cambia. Tiene que verse |
| **C. La tienda maneja el 400** | Nada de código nuestro | Solo sirve si la tienda tiene su propio precio |

**Recomendación**: **A como regla, C si la tienda tiene precio propio.** B solo si hace falta de
verdad, porque un precio calculado contra un cliente que no es el real es exactamente la clase de
número que después nadie puede explicar.

Ojo con no confundir esto con las **altas de distribuidoras** (tenants), que es otro problema y
está en [multi-tenant-y-auth.md](multi-tenant-y-auth.md).

---

## ✅ Cerradas al construir la Fase 1 (2026-10-06)

- **El descuento del contrato se expone como PORCENTAJE** (`10` = 10%), no como fracción.
  Decidido por el usuario, por coherencia con el resto del entorno Axum. GESCOM da fracción y el
  conector multiplica por 100; Axum ya viene en porcentaje y pasa derecho. Hay un test de core
  (`LineaValorizadaTest`) que falla si un descuento sale sin convertir.
- **El endpoint lleva contrato propio y el catálogo entra desde el día 1** para explicar el
  descuento, no solo darlo.

## ✅ Cerradas con la doc de Axum (2026-10-06)

- **`ordenManual`**: gana el **valor más bajo**. Mi hipótesis previa (que el 3% de la fila de
  proveedor estaba incluido en la variante de orden 50) **no tenía respaldo y queda descartada**.
- **Dónde está el cliente**: `bonifId` **es** el filtro por cliente — el cliente lo lleva
  asignado, igual que `percepId` para percepciones. No hay entidad padre que buscar.
- **Qué hace una bonificación**: tres operaciones, no una — descuento (con tope), precio fijo y
  unidades sin cargo. Ya está modelado en `Operacion` (`bonif-core`).
- **Jerarquía de desempate**: canasta → orden manual → artículo → línea → rubro → grupo → marca →
  proveedor.

## ✅ Cerradas el 2026-10-06

- **Quién consume el gateway** → **el entorno de Axum**: la tienda virtual o alguna app de la
  suite. Consecuencias en `arquitectura.md` → "Dónde encaja en el entorno Axum".
- **Auth de nuestra API** → **`x-api-key`**, misma convención que el gateway de Axum. No se
  inventa otra: el consumidor ya la implementa.
- **El tenant va en la ruta** (`/v1/{tenant}/…`), y se llama *tenant*, igual que Axum y
  MotorFiscal. Un solo gateway multi-distribuidora, no uno por distribuidora.
- **El gateway guarda las credenciales de las distribuidoras** (usuario y clave de API, por
  variable de entorno). El token de Keycloak lo mintea él, en el mismo proceso que lo usa.
- **El gateway no calcula descuentos**: delega en `eval-pedido`.

---

## 1. ¿Qué nombres de campo espera la tienda en la respuesta? — **bloquea congelar el contrato**

La lección de MotorFiscal: copió los nombres de `facturasimpagas` (`totalIva`,
`percepcionIva`, …) para que la factura mapeara 1:1 sin traducción en el medio. Acá hay que hacer
lo mismo con la **línea de carrito de la tienda**.

**Recomendación**: antes de congelar los DTO, mirar cómo arma la tienda su línea hoy y qué campos
manda a MotorFiscal. Si la tienda ya tiene un `precioUnitario` / `neto` / `descuento`, usamos esos
nombres aunque no sean los que elegiríamos. Mientras tanto el contrato queda marcado como
**borrador**, no versionado como estable.

**Cerrado el 2026-10-07**: el **código de cliente es el mismo que en GESCOM**. No hace falta
traducción: lo que manda la tienda va derecho al `CodigoCliente` de `eval-pedido`.

**Pendiente**: la tienda va a pasar un JSON de ejemplo de lo que emite. Ese JSON define el
contrato de entrada. Lo que hay que mirar cuando llegue, en orden de impacto:

| Qué mirar | Por qué cambia algo |
|---|---|
| **¿De dónde sale el precio que ve el cliente, y con qué lista?** | **Lo más importante.** La lista que mandamos **cambia el precio** (verificado en vivo: `63175` en la lista 2 contra `59976` en la 3, mismo ítem y cantidad). Si la tienda muestra precios de una lista y no nos la manda, devolvemos un neto que no coincide con su carrito. Y el **porcentaje de descuento sale igual en los dos casos**, así que el error no se nota mirando el descuento — solo el importe. Detalle en `fuente-gescom-criterios.md` → "La lista de precios: qué hace y qué no" |
| **¿Manda el carrito entero o ítem por ítem?** | Los criterios se evalúan **sobre la venta completa** (`requiredQuantity`, condiciones de marca/rubro que miran todos los ítems). Pedir ítem por ítem da descuentos distintos a pedir el carrito. No es una optimización: es corrección |
| **¿Manda precio?** | Si la tienda ya tiene un precio y nosotros devolvemos otro, hay que decidir cuál manda antes de que lo descubra un cliente |
| **¿Unidad y bultos?** | `CodigoUnidad` / `UnidadFactor`. Hoy ponemos `"Unidad"` y factor 1 por defecto; si la tienda vende por bulto, eso está mal |
| **¿Qué hace con la respuesta?** | Define los nombres de salida. Si arma la línea de factura, copiamos esos nombres |

---

## 2. ¿El gateway se llama antes o dentro del flujo que ya llama a MotorFiscal?

Son dos llamadas del mismo checkout, en orden: bonificaciones primero (da el neto), MotorFiscal
después (tributa sobre ese neto).

**Recomendación**: que la tienda haga las dos llamadas, en ese orden, y que **ninguno de los dos
servicios llame al otro**. Encadenarlos por dentro los acopla y hace que una caída de uno voltee
al otro. Si más adelante se quiere una sola llamada, se arma un endpoint que orqueste — pero esa
es una decisión aparte, con su propio costo.

---

## 3. ¿Hace falta base de datos?

**Recomendación (2026-10-07): SÍ, y ya no por auditoría: por configuración.** Esto escala a ~1000
tiendas, y 1000 × (host, realm, usuario, clave, api-key) no entra en variables de entorno. Es
SQL Server + Flyway, igual que `api-impuestos`. Ver
[multi-tenant-y-auth.md](multi-tenant-y-auth.md).

Los otros motivos que ya estaban, y que ahora vienen de arriba:

- **auditoría**: "¿qué le respondimos a la tienda el martes?" — probable que haga falta, porque
  es plata y alguien va a reclamar;
- **histórico de criterios**: detectar qué promo cambió y cuándo (GESCOM no versiona nada);
- **resiliencia**: responder con el último catálogo conocido si el ERP está caído.

Si entra, es SQL Server + Flyway, igual que `api-impuestos` (el servidor ya lo tiene).

---

## 4. ¿Qué pasa si el ERP está caído en pleno checkout?

El gateway delega el número en `eval-pedido`: si GESCOM no responde, no hay descuento que
devolver. Las opciones son devolver error (la tienda decide), o devolver precio sin descuento
marcado como degradado.

**Recomendación**: **devolver error con código de dominio** (`ERP_NO_DISPONIBLE`, 503) y que la
tienda decida. Devolver un precio sin descuento es exactamente el caso que la regla dura prohíbe:
el cliente compraría más caro de lo que le corresponde y nadie se enteraría. **Confirmar con
negocio**, porque la alternativa es perder la venta.

---

## 5. ¿La tienda nos llama desde el navegador o desde su servidor? — **tiene filo de seguridad**

Surgió de una pregunta sobre CORS. Dos cosas distintas:

**CORS con GESCOM no nos afecta.** CORS lo aplica el navegador, no el servidor, y nosotros le
pegamos a GESCOM **servidor a servidor**: no hay preflight ni `Origin` que valga. Si GESCOM manda
o no cabeceras CORS nos da igual.

Y lo que es más fuerte: **GESCOM no está hecho para que le pegue un navegador en absoluto.** Su
auth es grant `password` con el usuario y la clave de API de la distribuidora. Usarlo desde el
front significaría poner esa clave en el navegador, donde cualquiera la lee. **Ese es, por sí
solo, un motivo de existir de este gateway**, aparte de normalizar: mueve la credencial al
servidor.

**Donde CORS sí importa es en nuestra propia API**, y ahí depende de quién nos llama:

| Si la tienda nos llama… | Entonces |
|---|---|
| **desde su servidor** (como hace hoy con MotorFiscal) | no hace falta CORS, y el `x-api-key` queda del lado servidor, que es donde tiene que estar |
| **desde el navegador** | hay que configurar CORS con una lista explícita de orígenes… **y el `x-api-key` queda expuesto en el front** |

> ⛔ **Una `x-api-key` en el navegador es pública.** Cualquiera la saca del DevTools y consulta las
> bonificaciones de cualquier cliente de cualquier distribuidora. Si la tienda necesita llamarnos
> desde el front, el `x-api-key` **no alcanza** como esquema de auth y hay que ir a otra cosa
> (token corto emitido por el backend de la tienda, por ejemplo).

**Recomendación**: **que nos llame desde el servidor**, igual que ya hace con MotorFiscal. Es el
patrón que el equipo ya tiene andando, evita CORS entero y mantiene la credencial donde
corresponde. Confirmar antes de implementar la auth.

Dato de contexto: MotorFiscal resolvió su CORS sirviendo el panel desde el **mismo origen** que la
API. O sea, el equipo ya decidió una vez que la forma de lidiar con CORS es no tenerlo.

---


## 6. ¿Qué es Chess y qué aporta?

No hay una sola mención en `C:\Dev\docs`. **Recomendación**: cuando se acerque, arrancar por el
método de reversing que ya funcionó con GESCOM y escribir la referencia en `C:\Dev\docs` antes de
escribir código. Mientras tanto, **no reservar lugar para Chess en el modelo**: una abstracción
hecha para un sistema que nadie vio casi siempre sale mal.

---

## 7. ¿Dónde se deploya?

Falta definir servidor, puerto, y si va detrás de IIS. Si va detrás de un IIS que lo cuelga como
aplicación anidada, **hay que resolver el prefijo de ruta desde el día uno**: en `api-impuestos`
ese problema llegó a producción tres veces (Swagger y el panel armando URLs absolutas contra la
raíz del dominio). Toda URL que la app arme para sí misma sale de configuración, nunca asumida.

**Recomendación**: definirlo antes de la Fase 4, y si hay IIS anidado, probar **a través** del
proxy, nunca contra `localhost:8081` directo. Lo más probable es que convenga el mismo servidor
donde ya corre MotorFiscal, por una razón boba pero real: la tienda ya le pega ahí.

---

