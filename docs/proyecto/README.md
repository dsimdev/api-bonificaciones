# docs/proyecto/ — lo específico de api-bonificaciones

El método portable vive en `docs/metodo/`. Acá va todo lo que es de este proyecto y cambia.

| Archivo | Qué contiene |
|---|---|
| [arquitectura.md](arquitectura.md) | Las piezas, cómo se hablan, y la decisión más cara con sus alternativas descartadas |
| [fuente-axum-bonificaciones.md](fuente-axum-bonificaciones.md) | El endpoint de bonificaciones de Axum: campos, diferencias con GESCOM, y lo que falta |
| [plan-de-fases.md](plan-de-fases.md) | Qué se construye y en qué orden, con el criterio de salida de cada fase |
| [decisiones-abiertas.md](decisiones-abiertas.md) | Lo que falta decidir, cada una con recomendación. Se vacía a medida que se cierran |
| [informacion-que-falta.md](informacion-que-falta.md) | Datos y accesos concretos que hacen falta para avanzar, y quién los tiene |
| [entorno-local.md](entorno-local.md) | Cómo levantarlo, cómo cargar credenciales, cómo probar contra un ERP real |

La referencia de la API de GESCOM **no está acá**: es cross-project y vive en
`C:\Dev\docs\gescom\eval-pedido.md`. Si algo nuevo se descubre de GESCOM, se actualiza ahí, no
se copia acá.

Dos reglas que hacen que esto sirva:

1. **Las contradicciones de los requerimientos se escriben, no se resuelven por asunción.**
2. **Una decisión cerrada se mueve a la memoria (`MEMORY.md` → "Temas CERRADOS")** con fecha y
   motivo. Si no, se vuelve a discutir dentro de tres sesiones.
