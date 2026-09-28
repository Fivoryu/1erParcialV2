# Códigos QR de acceso

Códigos para llegar a los repositorios del proyecto y al despliegue en la nube desde un teléfono, pensados
para el documento impreso y para la defensa.

> **El QR del despliegue apunta a algo que ya no existe.** `web-aws.png` lleva a
> `https://34.239.56.54.nip.io`, y el 2026-09-23 se destruyó el despliegue completo en AWS para dejar de
> consumir crédito: instancia, volumen, IP elástica y repos ECR. Ese código **no abre nada hoy**. Los cuatro QR
> de repositorios siguen siendo válidos.

| Archivo | Destino | Qué abre |
| --- | --- | --- |
| `repo-raiz.png` | `https://github.com/Fivoryu/sw1_p1_2026_1` | Repositorio raíz: documentación, `odd/`, herramientas de despliegue y pruebas |
| `repo-backend.png` | `https://github.com/Fivoryu/sw1_p1_2026_1-backend` | Backend de la plataforma (Spring Boot, CU-01 a CU-16) |
| `repo-mobile.png` | `https://github.com/Fivoryu/sw1_p1_2026_1-mobile` | Cliente Flutter, con el asistente y el modelo local on-device |
| `repo-web.png` | `https://github.com/Fivoryu/sw1_p1_2026_1-web` | Editor web (React + TypeScript + Vite) |
| `web-aws.png` | `https://34.239.56.54.nip.io` | **El editor web en la nube**, con certificado de Let's Encrypt |

## Cómo se generaron

```bash
npx qrcode -o docs/qr/repo-raiz.png -w 600 -e M "https://github.com/Fivoryu/sw1_p1_2026_1"
```

`-w 600` fija 600×600 píxeles, suficiente para imprimir en tamaño pequeño, y `-e M` usa corrección de
errores media, que tolera suciedad o una esquina tapada.

## Verificación

Los cinco archivos se decodificaron de vuelta a texto y coinciden carácter por carácter con la URL
declarada en la tabla. La decodificación se hizo leyendo el PNG con el detector de OpenCV, no confiando en la
cadena que se le pasó al generador:

```text
OK    repo-raiz.png      -> https://github.com/Fivoryu/sw1_p1_2026_1
OK    repo-backend.png   -> https://github.com/Fivoryu/sw1_p1_2026_1-backend
OK    repo-mobile.png    -> https://github.com/Fivoryu/sw1_p1_2026_1-mobile
OK    repo-web.png       -> https://github.com/Fivoryu/sw1_p1_2026_1-web
OK    web-aws.png        -> https://34.239.56.54.nip.io
```

Un QR que apunta a otra dirección es un defecto real en un documento impreso, así que la comprobación
se hizo leyendo el PNG, no confiando en la cadena que se le pasó al generador.

## El QR del despliegue, y su condición

`web-aws.png` se añadió el 2026-09-23, cuando el editor quedó publicado en la raíz del ingreso. Antes no
existía a propósito: la instancia estaba **detenida** y su puerto cerrado, así que un QR a la URL pública no
habría abierto nada.

Apunta al **nombre con certificado**, no a la IP, y eso es deliberado: el editor necesita un contexto seguro
porque usa Web Crypto, y sobre `http://` en una IP el navegador no se lo da. Un QR a `http://34.239.56.54:8080`
abriría una página que no funciona. El nombre `34.239.56.54.nip.io` lo resuelve el servicio público `nip.io`
a la IP de la instancia, así que no hubo que comprar un dominio.

Tiene una condición que conviene decir en voz alta antes de imprimirlo: **el QR solo funciona con la
instancia encendida**, y encenderla factura cómputo (unos $0.079/hora de `c7i-flex.large`). Si el documento
se imprime para una defensa, conviene encender la instancia ese día y no antes. Las credenciales del inicio
de sesión de demostración son públicas —están en el repositorio—, así que la plataforma queda alcanzable por
cualquiera mientras esté encendida.

El estado del despliegue, sus medidas y sus límites están en `tools/aws-deploy/REPORT-web-ingress.md` y
`tools/aws-deploy/REPORT-cloud-platform.md`.
