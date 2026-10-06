# Decisiones abiertas

Cada una con **recomendación**, no solo la pregunta. Cuando una se cierra, se borra de acá y se
anota en la memoria del proyecto (`MEMORY.md` → "Temas CERRADOS") con fecha y motivo.

---

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

**Recomendación**: antes de congelar los DTO de la Fase 2, mirar cómo arma la tienda su línea
hoy y qué campos manda a MotorFiscal. Si la tienda ya tiene un `precioUnitario` / `neto` /
`descuento`, usamos esos nombres aunque no sean los que elegiríamos. Mientras tanto el contrato
queda marcado como **borrador**, no versionado como estable.

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

## ✅ Resuelta el 2026-10-06: qué devuelve el endpoint de Axum

**Definiciones, no resultados.** Filas de bonificación con filtros y porcentaje; nada que
valorice un pedido. Axum no tiene motor. Análisis completo en
[fuente-axum-bonificaciones.md](fuente-axum-bonificaciones.md); la consecuencia de diseño está en
`arquitectura.md` → "La regla de delegar, corregida".

Lo que **sigue abierto** de esa misma respuesta son tres cosas concretas, abajo (#5, #6, #7).

---

## 5. ¿Qué distingue `ordenManual` 50 de `ordenManual` 90? — **bloquea el motor de Axum**

Decenas de filas vienen en pares idénticos salvo por el descuento y el `ordenManual`, y **la
diferencia es siempre exactamente 3 puntos** (18/15, 11/8, 26/23, 28/25, 23/20). La única fila con
`listaDePrecios` cargada (lista 5, proveedor 00009, orden 100) tiene `descuento: "3"`.

**Hipótesis**: ese 3% está incluido en la variante de orden 50 y no en la de 90, y algo que no
está en estas filas decide cuál gana.

**No se puede resolver leyendo el payload.** Y elegir mal es cobrar 15% donde iba 18%.
**Recomendación**: preguntarle al equipo del gateway qué significa `ordenManual` y cómo se
resuelve el empate, antes de escribir el motor.

---

## 6. ¿Dónde están el cliente y la vigencia de una bonificación de Axum?

Las filas **no tienen nada de cliente** (ni código, ni tag, ni lista asignada) **ni fechas**.
Todas cuelgan de un `bonifId`, cuya entidad padre no vimos.

Si la segmentación y la vigencia no están en el padre, entonces **no existen**, y eso repite el
`Hallazgo 2` de `integracion-axum.md`: sin vigencia no se puede reconstruir qué bonificación
aplicaba en una fecha pasada.

**Recomendación**: conseguir el shape del padre antes de modelar nada. Si efectivamente no hay
vigencia, es un hallazgo para negocio —no un problema técnico— y es el mismo hueco que MotorFiscal
terminó llenando versionando al ingerir.

---

## 7. ¿Los descuentos de Axum se acumulan?

`topeDescuento` es idéntico a `descuento` en todas las filas observadas. O es redundante, o solo
difiere cuando varios descuentos se apilan y el tope los limita.

**Recomendación**: pedir un caso donde difieran. Si se acumulan, el orden de aplicación pasa a ser
parte del contrato y hay que testearlo explícitamente.

---

## 8. ¿Qué más le pedimos a Axum, aparte de bonificaciones?

- **resolver atributos de cliente/artículo desde Axum** en vez de desde GESCOM, si la tienda ya
  los tiene ahí y queremos ahorrarnos llamadas;
- **enriquecer la respuesta** con descripción, rubro o línea del artículo;
- **validar** que lo que ve la tienda y lo que ve GESCOM es el mismo artículo.

---

## 9. ¿Qué es Chess y qué aporta?

No hay una sola mención en `C:\Dev\docs`. **Recomendación**: cuando se acerque, arrancar por el
método de reversing que ya funcionó con GESCOM y escribir la referencia en `C:\Dev\docs` antes de
escribir código. Mientras tanto, **no reservar lugar para Chess en el modelo**: una abstracción
hecha para un sistema que nadie vio casi siempre sale mal.

---

## 10. ¿Dónde se deploya?

Falta definir servidor, puerto, y si va detrás de IIS. Si va detrás de un IIS que lo cuelga como
aplicación anidada, **hay que resolver el prefijo de ruta desde el día uno**: en `api-impuestos`
ese problema llegó a producción tres veces (Swagger y el panel armando URLs absolutas contra la
raíz del dominio). Toda URL que la app arme para sí misma sale de configuración, nunca asumida.

**Recomendación**: definirlo antes de la Fase 4, y si hay IIS anidado, probar **a través** del
proxy, nunca contra `localhost:8080` directo. Lo más probable es que convenga el mismo servidor
donde ya corre MotorFiscal, por una razón boba pero real: la tienda ya le pega ahí.

---

## 11. ¿El descuento se expone como fracción o como porcentaje?

**Recomendación**: **fracción**, igual que GESCOM. Que el número del gateway sea comparable uno a
uno con el del ERP ahorra una clase entera de bugs de conversión. Queda documentado en el
contrato y en el OpenAPI. (Ojo: Axum usa la convención opuesta en `percepIB` — "alícuota sobre
100" — así que esto hay que decirlo fuerte en la doc del integrador.)
