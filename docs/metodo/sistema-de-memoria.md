# Sistema de memoria

Claude Code guarda memoria en archivos, en
`~/.claude/projects/<slug-del-proyecto>/memory/`. Cada archivo = **un hecho**. `MEMORY.md` es el
índice que se carga en contexto al empezar cada sesión.

El sistema funciona. Lo que lo arruina es dejarlo crecer sin podarlo.

---

## Formato

```markdown
---
name: <slug-kebab-case>
description: <una línea — con esto se decide si la memoria es relevante>
metadata:
  type: user | feedback | project | reference
---

<el hecho. Para feedback/project, seguir con **Why:** y **How to apply:**>
<enlazar memorias relacionadas con [[su-name]]>
```

**Los tipos:**

- `user` — quién es el usuario (rol, expertise, preferencias)
- `feedback` — cómo quiere que trabajes. Correcciones y también enfoques confirmados. **Siempre
  con el porqué**: una regla sin su motivo se aplica mal en cuanto el contexto cambia
- `project` — trabajo en curso, objetivos, restricciones que NO se deducen del código ni del
  git log. Fechas relativas convertidas a absolutas ("el mes que viene" no significa nada
  releído en marzo)
- `reference` — punteros a recursos externos (URLs, dashboards, tickets)

**En `MEMORY.md`** va una línea por memoria: `- [Título](archivo.md) — gancho`. Nunca contenido.

---

## La regla que hace que todo esto sirva

> **La memoria es una foto del pasado, no estado vivo.**

Antes de afirmar que algo está hecho, pendiente o roto: **abrir el código**. grep, read, git log.
Nunca responder desde la memoria ni desde un changelog viejo.

En un proyecto anterior esto se aprendió a los golpes. El backlog se desfasó 7 versiones y se
ofrecieron como "features nuevas" cosas que ya estaban en producción hacía meses. Textual del
usuario: *"la guía ya está, lo vimos mil veces, mil veces actualizaste memoria y seguís diciendo
de la guía"*.

Las memorias de estado (backlog, auditorías, planes) se leen como **hipótesis a confirmar**, no
como verdad.

---

## Higiene: las tres cosas que la pudren

**1. El pendiente zombi.** Cuando el usuario corrige un ítem, hay que borrarlo de **TODAS** las
memorias, no solo de la que estaba abierta. Un pendiente muerto sobrevive en una auditoría vieja
que nadie limpió y reaparece tres sesiones después como si fuera nuevo. El procedimiento es
`grep -rn <tema>` sobre la carpeta entera y limpiar cada ocurrencia.

**2. El duplicado.** Antes de crear un archivo, buscar si ya existe uno que cubre el tema.
Actualizar el existente, no crear el hermano.

**3. Lo que no debería estar.** No guardar lo que el repo ya registra: estructura del código,
fixes pasados, historia de git, contenido de `CLAUDE.md`. Tampoco lo que solo importa en esta
conversación. Si el usuario pide recordar algo de eso, preguntar qué fue lo **no obvio** y
guardar eso.

---

## Sección "temas CERRADOS"

Vale la pena mantener en `MEMORY.md` una sección explícita de **cosas que ya se decidieron que
NO se hacen**. Sin ella, cada tanto se vuelve a ofrecer lo mismo que ya se descartó, y para el
usuario es la señal más clara de que no lo estás escuchando.

Formato: qué era, cuándo se cerró, y si fue por "ya está hecho" o por "se decidió que no".

---

## Cuándo escribir memoria

- El usuario te corrige → memoria `feedback` **con el porqué**
- Se toma una decisión de arquitectura o de producto que no queda escrita en el código →
  `project`
- Se cierra una auditoría → `project` con los hallazgos y su resolución
- Se descubre un gotcha caro (algo que costó una corrida, un deploy o un bug en prod) → `feedback`

**Cuándo NO:** después de cada feature. El changelog ya es eso. La memoria es para lo que el
código no dice.

---

## Cuando una lección trasciende al proyecto

Si la lección aplicaría igual en cualquier proyecto, además de la memoria local **volvé a
subirla al kit** (`metodo/lecciones.md` y, si merece ser regla permanente, una memoria en
`memoria-semilla/`). Es lo que hace que el kit siguiente arranque más arriba que este.
