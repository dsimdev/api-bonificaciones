# Decisiones abiertas

Cada una con **recomendación**, no solo la pregunta. Cuando una se cierra, se borra de acá y se
anota en la memoria del proyecto (`MEMORY.md` → "Temas CERRADOS") con fecha y motivo.

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
| **¿Manda `listaPrecio`?** | **Lo más importante.** Dyssa tiene criterios con condición `ListaPrecioVenta`: la lista **decide qué bonificación aplica**. Si no la mandamos, `eval-pedido` usa la lista por defecto del cliente y el descuento puede ser otro — y nadie se entera, porque la respuesta es igual de verosímil. Hoy el campo es opcional en nuestro contrato; si la tienda la tiene, pasa a obligatorio |
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

**Recomendación**: **no todavía.** Stateless + cache en memoria alcanza para las fases 1 a 3, y
no tener base es una pieza menos que operar. Entra el día que aparezca uno de estos:

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

## 6. ¿Cómo leemos los settings de bonificaciones de cada distribuidora? — **bloquea el motor de Axum**

La doc de Axum documenta tres configuraciones que **cambian el resultado** y que **no vienen en el
payload de bonificaciones**:

- `Bonificaciones.HabilitarOrdenManual` — si está off, se usa la prioridad automática en vez de
  `ordenManual`;
- `Bonificaciones.HabilitarFiltroSucursalVendedor` — si está off, se ignora el filtro de sucursal;
- **LP + cantidad de listas** — si está on, evalúa **todas** las listas y aplica **la de mayor
  beneficio**, ignorando el filtro de lista. Eso convierte la resolución en un `max()`.

Con el mismo payload y distintos settings, el descuento que corresponde es distinto.

**Recomendación**: preguntar si hay endpoint que los exponga. Si no lo hay, van como configuración
nuestra por tenant, **explícita y visible en `/health`** — un setting mal puesto que nadie ve es
un descuento mal calculado que nadie ve.

---

## 7. ¿La agregación por cantidad vale para todos los agrupadores o solo para canasta?

La doc muestra el ejemplo solo con **canasta**: el umbral (`cantidadSuperior`) se evalúa sobre la
**suma de los ítems del grupo**, no por ítem — 5 Coca + 5 Pepsi dispara una bonificación de "más
de 9", 5 Coca + 5 Fanta no.

Falta confirmar si lo mismo vale para grupo, rubro, línea, marca y proveedor.

**Recomendación**: asumir que **sí** agrega (es lo coherente) pero **no darlo por cierto**:
confirmarlo antes de la Fase 2 y dejar un test por cada agrupador. Si el motor evalúa por línea
donde debía agregar, la bonificación no dispara y nadie se entera hasta el reclamo.

---

## 8. ¿Se acumulan varias bonificaciones sobre el mismo ítem?

La jerarquía de Axum sugiere que **gana una sola** (la del filtro de mayor prioridad presente).
El flujo de la app —*"se borra la card del descuento aplicado y quedan las que se podrían
aplicar"*— sugiere que las demás siguen disponibles.

**Recomendación**: confirmarlo antes de escribir el motor. Si se acumulan, el orden de aplicación
es parte del contrato y hay que testearlo. Relacionado: `topeDescuento` es idéntico a `descuento`
en toda la muestra — **pedir un caso donde difieran**, porque un tope que nunca se activa o es
redundante, o es la señal de que sí se apilan.

---

## 9. ¿Qué más le pedimos a Axum, aparte de bonificaciones?

- **resolver atributos de cliente/artículo desde Axum** en vez de desde GESCOM, si la tienda ya
  los tiene ahí y queremos ahorrarnos llamadas;
- **enriquecer la respuesta** con descripción, rubro o línea del artículo;
- **validar** que lo que ve la tienda y lo que ve GESCOM es el mismo artículo.

---

## 10. ¿Qué es Chess y qué aporta?

No hay una sola mención en `C:\Dev\docs`. **Recomendación**: cuando se acerque, arrancar por el
método de reversing que ya funcionó con GESCOM y escribir la referencia en `C:\Dev\docs` antes de
escribir código. Mientras tanto, **no reservar lugar para Chess en el modelo**: una abstracción
hecha para un sistema que nadie vio casi siempre sale mal.

---

## 11. ¿Dónde se deploya?

Falta definir servidor, puerto, y si va detrás de IIS. Si va detrás de un IIS que lo cuelga como
aplicación anidada, **hay que resolver el prefijo de ruta desde el día uno**: en `api-impuestos`
ese problema llegó a producción tres veces (Swagger y el panel armando URLs absolutas contra la
raíz del dominio). Toda URL que la app arme para sí misma sale de configuración, nunca asumida.

**Recomendación**: definirlo antes de la Fase 4, y si hay IIS anidado, probar **a través** del
proxy, nunca contra `localhost:8080` directo. Lo más probable es que convenga el mismo servidor
donde ya corre MotorFiscal, por una razón boba pero real: la tienda ya le pega ahí.

---

