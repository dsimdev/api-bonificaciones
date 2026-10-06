# Decisiones abiertas

Cada una con **recomendación**, no solo la pregunta. Cuando una se cierra, se borra de acá y se
anota en la memoria del proyecto (`MEMORY.md` → "Temas CERRADOS") con fecha y motivo.

---

## 1. ¿Quién consume el gateway y para qué? — **bloquea las fases 3+**

Es la única que cambia de verdad el orden del trabajo. Candidatos:

- una **app de preventistas** que arma pedidos → prioridad absoluta a valorizar (Fase 2);
- un **panel / back-office** que audita promos → prioridad a explicar y comparar (Fase 3);
- **otra API** que necesita el dato → prioridad a batch y estabilidad de contrato (Fase 4);
- **nosotros mismos**, para dejar de pelear con GESCOM a mano → prioridad a cobertura de
  endpoints.

**Recomendación**: aunque no esté definido, **las fases 1 y 2 valen igual en los cuatro casos**.
Se arranca por ahí y se decide esto antes de la Fase 3.

---

## 2. ¿Cómo se autentica el consumidor contra nuestro gateway?

Hoy no hay auth. El gateway va a guardar credenciales de varias distribuidoras: exponerlo sin
auth es regalar el acceso a los datos comerciales de todas.

**Recomendación**: **`x-api-key` por consumidor**, igual que la API pública de Axum
(`C:\Dev\docs\axum\integracion-axum.md`). Es lo que el equipo ya sabe integrar, no necesita
infraestructura nueva y alcanza para servicio-a-servicio. Si más adelante consume un usuario
final desde el navegador, se evalúa JWT.

**Obligatorio antes de la Fase 4.** Mientras tanto, solo red local.

---

## 3. ¿El gateway guarda las credenciales de las distribuidoras, o se las pasa el consumidor?

**Recomendación**: **las guarda**. El token de GESCOM dura 5 minutos; pedirle al consumidor que
relaye credenciales o tokens termina en tokens vencidos y en credenciales dando vueltas por más
lugares. Van por variable de entorno, nunca al repo.

La contra, que hay que aceptar explícitamente: el gateway se vuelve un objetivo valioso. Eso es
lo que obliga a la decisión 2.

---

## 4. ¿Hace falta base de datos?

**Recomendación**: **no todavía.** Stateless + cache en memoria alcanza para las fases 1 a 3, y
no tener base es una pieza menos que operar. Entra el día que aparezca uno de estos:

- **auditoría**: "¿qué le respondimos a este preventista el martes?";
- **histórico de criterios**: detectar qué promo cambió y cuándo (GESCOM no versiona nada);
- **resiliencia**: poder responder con el último catálogo conocido si el ERP está caído.

Si entra, es SQL Server + Flyway, igual que `api-impuestos` (el servidor ya lo tiene).

---

## 5. ¿Un solo gateway para todas las distribuidoras, o uno por distribuidora?

**Recomendación**: **uno solo, multi-distribuidora** (la distribuidora va en la ruta). Es lo que
ya soporta la configuración actual. Un proceso por distribuidora multiplica el deploy sin dar
nada a cambio, salvo que exista un requisito de aislamiento que hoy no conocemos.

---

## 6. ¿Qué pasa con SIGMA / GEWINN?

**Recomendación**: diseñar el puerto (ya está) e **implementar solo GESCOM**. No inventar
abstracciones para un ERP que nadie vio. Cuando SIGMA entre de verdad, el puerto se ajusta con
información real — y si hay que romperlo, se rompe, es código interno.

---

## 7. ¿Dónde se deploya?

Falta definir servidor, puerto, y si va detrás de IIS. Si va detrás de un IIS que lo cuelga como
aplicación anidada, **hay que resolver el prefijo de ruta desde el día uno**: en `api-impuestos`
ese problema llegó a producción tres veces (Swagger y el panel armando URLs absolutas contra la
raíz del dominio). Toda URL que la app arme para sí misma sale de configuración, nunca asumida.

**Recomendación**: definirlo antes de la Fase 4, y si hay IIS anidado, probar **a través** del
proxy, nunca contra `localhost:8080` directo.

---

## 8. ¿El descuento se expone como fracción (0.1) o como porcentaje (10)?

**Recomendación**: **fracción**, igual que GESCOM. Que el número del gateway sea comparable uno a
uno con el del ERP ahorra una clase entera de bugs de conversión. Queda documentado en el
contrato y en el OpenAPI.
