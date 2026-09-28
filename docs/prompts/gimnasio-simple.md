# Prompt del gimnasio simple, listo para pegar

> Dos mensajes, en este orden. El segundo **solo funciona después de aplicar el primero**: las relaciones
> necesitan que las clases ya existan en el modelo. Pegar los dos de una vez hace que el segundo no encuentre
> a qué conectarse.

Cada mensaje respeta el presupuesto del asistente: **clases + relaciones + atributos ≤ 12** en una misma
petición. Si se pasa, el texto entero cae al modelo lento y su respuesta deja de ser confiable.

---

## Mensaje 1 — el vocabulario del gimnasio

**9 operaciones**: 5 clases + 4 atributos.

```text
crea las clases Socio, Membresia, Pago, Entrenador, Sesion; atributos de Socio son nombre: String, email: String; atributos de Membresia son precio: Decimal; atributos de Pago son monto: Decimal
```

Al aplicarlo quedan cinco clases en el lienzo:

| Clase | Atributos |
| --- | --- |
| `Socio` | `nombre: String`, `email: String` |
| `Membresia` | `precio: Decimal` |
| `Pago` | `monto: Decimal` |
| `Entrenador` | *(sin atributos)* |
| `Sesion` | *(sin atributos)* |

---

## Mensaje 2 — cómo se conectan

**4 operaciones**: 4 relaciones.

```text
conecta Socio con Membresia 1 0..*; conecta Membresia con Pago 1 0..*; conecta Entrenador con Sesion 1 0..*; conecta Socio con Sesion 0..* 0..*
```

| Origen | Destino | Multiplicidades | Lectura |
| --- | --- | --- | --- |
| `Socio` | `Membresia` | `1` a `0..*` | un socio tiene muchas membresías |
| `Membresia` | `Pago` | `1` a `0..*` | una membresía se cobra en varios pagos |
| `Entrenador` | `Sesion` | `1` a `0..*` | un entrenador dicta varias sesiones |
| `Socio` | `Sesion` | `0..*` a `0..*` | un socio asiste a varias sesiones |

Las cuatro quedan como **asociación**, que es el tipo por defecto. Si quieres otro tipo, agrega la palabra al
final de esa relación: `composicion`, `agregacion`, `herencia`, `dependencia`.

---

## Si quieres más, en mensajes aparte

El presupuesto es **por mensaje**, así que se amplía mandando más mensajes, no uno más largo. Ejemplos que
respetan el tope:

```text
agrega los atributos telefono: String, fechaAlta: DateTime a la clase Socio
```

```text
agrega los atributos fecha: DateTime a la clase Pago; agrega los atributos especialidad: String a la clase Entrenador
```

---

## Dos reglas que no son obvias

**No llames a una clase con una palabra que el asistente usa como sintaxis.** `clase`, `tipo`, `relacion`,
`de`, `la`, `el`, `con`, `a`, `entre`, `y`. El resolutor las borra de las relaciones antes de leer los
nombres, así que una clase llamada `Clase` desaparece de cualquier relación que la mencione y el mensaje
entero cae al modelo lento. Por eso la clase de las sesiones se llama `Sesion` y no `Clase`.

**Escribe los nombres sin acentos y sin espacios.** `Membresia`, no `Membresía` ni `Tipo de membresía`. Estos
nombres se convierten en clases Java cuando generes el backend desde el diagrama, y un acento ahí se vuelve un
problema más adelante. Si quieres la palabra completa en el diagrama, renómbrala después en el lienzo.
