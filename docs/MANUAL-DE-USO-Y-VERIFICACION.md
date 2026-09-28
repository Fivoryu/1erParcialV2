# Manual de uso y verificación

> **El despliegue ya no existe.** El 2026-09-23, por decisión del usuario, se **terminó la instancia**, se borró
> su volumen, se liberó la IP elástica y se eliminaron los repositorios ECR, para dejar de consumir crédito.
> **Ninguna URL de este manual responde hoy**: `https://34.239.56.54.nip.io` da timeout, igual que
> `http://34.239.56.54:8080`. El documento se conserva como registro de lo que se construyó y midió, no como
> guía de algo que esté en pie. Los modelos de los proyectos se guardaron antes de destruirlo, en
> `docs/xmi-backup/`, y el arnés para reproducir ese respaldo es `tools/gym-diagram/backup_models.py`.
>
> Todo lo de este documento está **medido** sobre el despliegue real del 2026-09-23, salvo lo que la sección
> final marca como no verificado. Las URLs y los identificadores son los de esa corrida; los identificadores
> dinámicos se obtienen siempre de la plataforma, como se explica en cada paso.

## 1. Qué está desplegado

La plataforma del diagramador y su asistente corren en **una instancia de AWS**, detrás de **un solo ingreso**.
Cada backend generado se publica como una **ruta**, no como un puerto propio.

| Pieza | URL o ubicación | Notas |
| --- | --- | --- |
| **Editor web** | **`https://34.239.56.54.nip.io`** | La raíz abre el editor. Certificado de Let's Encrypt |
| **Ingreso (Caddy)** | `https://34.239.56.54.nip.io` (443), `http://34.239.56.54.nip.io` (80, redirige) y `http://34.239.56.54:8080` | El 8080 en HTTP simple queda para el teléfono |
| **Plataforma (API)** | `+/auth/*`, `+/projects*`, `+/actuator/*` y `+/sync/*` | `GET /actuator/health` responde `{"status":"UP"}` |
| **Backend generado de prueba** | `+/apps/libros` | Salud: `+/actuator/health`. Descriptor: `+/meta/domain` |
| Datos de ese backend | `+/api/autors` y `+/api/libros` | Las rutas salen del descriptor, no se adivinan |
| Modelo de lenguaje | interno, `127.0.0.1:8082` en la instancia | No es público; lo consume la plataforma |
| Consola de AWS | `https://us-east-1.console.aws.amazon.com/ec2/home?region=us-east-1#InstanceDetails:instanceId=i-056df11455cde02f2` | Instancia `i-056df11455cde02f2`, `c7i-flex.large`, us-east-1 |
| Repositorios | `github.com/Fivoryu/sw1_p1_2026_1` y los sufijos `-backend`, `-mobile`, `-web` | Hay códigos QR en `docs/qr/` |

**Por qué el navegador usa el nombre con certificado y no la IP.** El editor necesita un **contexto seguro**:
construye su identidad con `crypto.randomUUID` y firma su persistencia con `crypto.subtle.digest`, y el
navegador expone esas APIs solo sobre HTTPS o en `localhost`. Medido en un navegador real: en
`http://34.239.56.54:8080` el origen da `isSecureContext: false` y el editor no inicializa. El nombre
`34.239.56.54.nip.io` lo resuelve el servicio público `nip.io` a la IP de la instancia, así que **no hay
dominio comprado**; el certificado lo emite Let's Encrypt y Caddy lo renueva solo.

La plataforma sigue escuchando en `127.0.0.1:8081` **dentro** de la instancia: su puerto no está abierto a
internet, y solo se llega a ella a través del ingreso o de un túnel SSH. El editor se publica desde
`/opt/web`, montado **de solo lectura** en el ingreso; se republica con `tools/aws-deploy/deploy-web.ps1`.

El editor toma **cuatro caminos exactos** (la raíz, `/index.html`, `/assets/*` y `/favicon.ico`) y todo lo
demás sigue siendo de la plataforma. Es a propósito: la API también mapea `/sync/*` en la raíz, así que
enumerar sus prefijos en el ingreso habría tapado cualquier ruta nueva. La consecuencia declarada es que un
enlace profundo como `/editor` no lo sirve el editor, porque no tiene router de cliente.

## 2. Verificación en cinco minutos

Reemplaza `PROJECT_ID` por el proyecto que quieras (paso 3 de la sección siguiente) y ejecuta tal cual.

```bash
A=http://34.239.56.54:8080                 # el ingreso
U='X-User-Id: user01@diagrammer.local'

# 1. el editor se publica en la raíz
curl -s $A/ | grep -o '<title>[^<]*</title>'       # <title>UML 2.5 Editor</title>

# 2. la plataforma responde en sus propios caminos
curl -s $A/actuator/health          # 200
curl -s -o /dev/null -w '%{http_code}\n' $A/sync/snapshot   # 400: la API no quedó tapada

# 3. el backend generado responde y declara su dominio
curl -s $A/apps/libros/actuator/health                                            # {"status":"UP"}
curl -s $A/apps/libros/meta/domain                                                # entidades Autor y Libro

# 4. el backend hace su trabajo (crear, listar, leer, actualizar, borrar)
curl -s -H 'Content-Type: application/json' -X POST $A/apps/libros/api/autors \
     -d '{"nombre":"Bioy Casares","nacionalidad":"Argentina"}'                     # 201 con id
curl -s -H 'Content-Type: application/json' -X POST $A/apps/libros/api/libros \
     -d '{"titulo":"La invencion de Morel","anio":"1940","autorId":"<id del autor>"}'  # 201
curl -s $A/apps/libros/api/libros                                                  # 200 con el libro

# 5. el backend rechaza lo que no cumple su contrato
curl -s -H 'Content-Type: application/json' -X POST $A/apps/libros/api/libros \
     -d '{"titulo":"Sin autor","anio":"2020"}'                                     # 400 VALIDATION_ERROR
```

Lo último importa tanto como lo anterior: un backend que acepta cualquier cuerpo no está validando. El
descriptor declara qué campo es obligatorio, así que el rechazo es la prueba de que el contrato viaja con el
artefacto.

### Lo que el teléfono lee

La app no conoce ninguna URL de backend: las pide a la plataforma y usa la que el registro publica.

```bash
JAR=/tmp/cookies.txt
curl -s -c $JAR -H "$U" -H 'Content-Type: application/json' \
     -X POST $A/auth/demo-login -d '{"accountId":"USER_01"}'                       # 200
curl -s -b $JAR -H "$U" $A/projects/PROJECT_ID/deployments                          # baseUrl + descriptorUrl
```

## 3. Manual: el flujo completo, paso a paso

### Paso 0 — Dejar el sistema en pie

```bash
aws ec2 start-instances --instance-ids i-056df11455cde02f2
aws ec2 wait instance-status-ok --instance-ids i-056df11455cde02f2
ssh -i ~/.ssh/miraki-key.pem ubuntu@34.239.56.54 \
  "cd ~/platform && docker compose -f docker-compose.cloud.yml up -d"
```

El modelo de lenguaje vuelve solo (tiene política de reinicio), pero la plataforma y su base **no**: hay que
levantarlas con esa orden. La instancia tiene 3,8 GB de memoria y ya conviven con ella el modelo y los
backends desplegados.

### Paso 1 — Iniciar sesión en la plataforma

```bash
JAR=/tmp/cookies.txt
U='X-User-Id: user01@diagrammer.local'
A=http://34.239.56.54:8080
curl -s -c $JAR -H "$U" -H 'Content-Type: application/json' \
     -X POST $A/auth/demo-login -d '{"accountId":"USER_01"}'
```

Ese inicio de sesión es de demostración: no exige credencial real. Es la razón por la que la plataforma no se
publica sin pensar, y por la que conviene apagarla cuando no se use.

### Paso 2 — Crear un proyecto

```bash
S=$(date +%s)
UUID=$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n' | sed -E 's/(.{8})(.{4})(.{4})(.{4})(.{12})/\1-\2-\3-\4-\5/')
P=$(curl -s -b $JAR -c $JAR -H "$U" -H 'Content-Type: application/json' -X POST $A/projects \
  -d "{\"name\":\"Mi proyecto $S\",\"description\":\"Prueba\",\"template\":\"EMPTY\",
       \"operationId\":\"p-$S\",\"idempotencyKey\":\"p-$S\"}")
PROJECT_ID=$(echo "$P" | grep -o '"projectId":"[^"]*"' | cut -d'"' -f4)
MODEL_ID=$(echo "$P"   | grep -o '"modelId":"[^"]*"'   | cut -d'"' -f4)
echo "$PROJECT_ID $MODEL_ID"
```

### Paso 3 — Pedirle un modelo al asistente

```bash
R=$(curl -s -b $JAR -c $JAR -H "$U" -H 'Content-Type: application/json' \
  -X POST $A/projects/$PROJECT_ID/proposals \
  -d "{\"schemaVersion\":1,\"kind\":\"TEXT\",\"modelId\":\"$MODEL_ID\",\"baseRevision\":0,
       \"requestId\":\"$UUID\",
       \"text\":\"crea las clases Libro y Autor; los atributos de Libro son titulo y anio; los atributos de Autor son nombre y nacionalidad; conecta Libro con Autor\"}")
echo "$R" | grep -o '"status":"[^"]*"' | head -1
PROPOSAL_ID=$(echo "$R" | grep -o '"proposalId":"[^"]*"' | head -1 | cut -d'"' -f4)
```

Dos comportamientos medidos, y conviene conocerlos antes de sacar conclusiones:

| Frase | Tiempo | Resultado |
| --- | --- | --- |
| Estructurada (`crea las clases X e Y; … conecta X con Y`) | **0,5 a 1 s** | Propuesta válida, resuelta por el intérprete determinista **sin usar el modelo** |
| En lenguaje libre | **6 a 12 s** | Usa el modelo; de 4 intentos medidos, **2 dieron propuesta válida** |

Cuando el modelo devuelve un grafo incompleto, por ejemplo una relación hacia una clase que no declaró, la
plataforma **rechaza** la propuesta con `PROVIDER_INVALID_OUTPUT`. Es correcto: prefiere no construir un modelo
inválido. El registro del modelo está en la instancia:

```bash
ssh -i ~/.ssh/miraki-key.pem ubuntu@34.239.56.54 "docker logs platform-qwen-1 2>&1 | tail -20"
```

### Paso 4 — Confirmar la propuesta

```bash
curl -s -b $JAR -c $JAR -H "$U" -H 'Content-Type: application/json' \
  -X POST $A/projects/$PROJECT_ID/proposals/$PROPOSAL_ID/review-decision -d '{"action":"CONFIRM"}'
```

Esto crea la **revisión 1** del modelo. Ese número no es un detalle: ver el paso 5.

### Paso 5 — Generar el backend

```bash
V=$(curl -s -b $JAR -H "$U" $A/projects/$PROJECT_ID/versions)
VERSION_ID=$(echo "$V" | grep -o '"versionId":"[^"]*"' | tail -1 | cut -d'"' -f4)   # la revision mas alta

T=$(curl -s -b $JAR -c $JAR -H "$U" -H 'Content-Type: application/json' \
     -X POST $A/projects/$PROJECT_ID/generation-targets \
     -d "{\"targetKey\":\"t-$S\",\"name\":\"PostgreSQL\",\"databaseName\":\"mi_base_$S\",
          \"databaseSchema\":\"public\",\"connectionConfigRef\":\"t-$S\",
          \"configurationRevision\":1,\"databaseEngine\":\"POSTGRESQL\"}")
TARGET_ID=$(echo "$T" | grep -o '"targetId":"[^"]*"' | head -1 | cut -d'"' -f4)

P2=$(curl -s -b $JAR -H "$U" $A/projects/$PROJECT_ID/generation-profiles)
PROFILE_ID=$(echo "$P2"  | grep -o '"profileId":"[^"]*"'  | head -1 | cut -d'"' -f4)
TEMPLATE_ID=$(echo "$P2" | grep -o '"templateId":"[^"]*"' | head -1 | cut -d'"' -f4)

G=$(curl -s -b $JAR -c $JAR -H "$U" -H 'Content-Type: application/json' \
     -X POST $A/projects/$PROJECT_ID/generations \
     -d "{\"modelId\":\"$MODEL_ID\",\"versionId\":\"$VERSION_ID\",\"targetId\":\"$TARGET_ID\",
          \"profileId\":\"$PROFILE_ID\",\"templateId\":\"$TEMPLATE_ID\",\"targetConfigRevision\":1}")
RUN_ID=$(echo "$G" | grep -o '"runId":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "$G" | grep -o '"state":"[^"]*"' | head -1      # SUCCEEDED
```

Dos advertencias medidas:

1. **Usa la revisión más alta del modelo.** Con `revision 0` (anterior a confirmar la propuesta) la generación
   falla, correctamente: no hay nada que generar todavía.
2. **La plataforma reutiliza el artefacto almacenado** cuando modelo, target, perfil y plantilla son idénticos:
   devuelve el mismo ZIP en un segundo. Si cambias una plantilla en el código, las generaciones no cambian hasta
   que uses un target nuevo o cambie la fila de la plantilla en la base.

### Paso 6 — Desplegarlo detrás del ingreso, con una orden

```powershell
powershell.exe -NoProfile -File tools/aws-deploy/deploy-backend.ps1 -Target ingress `
  -ProjectId <PROJECT_ID> -RunId <RUN_ID> `
  -DeploymentName "mi-api-$(Get-Date -Format HHmmss)" -DomainLabel "Libro-Autor" `
  -IngressSlug mi-api -IngressHost 34.239.56.54 -IngressPort 8080 `
  -Ec2Host 34.239.56.54 -Ec2User ubuntu -SshKey "$HOME\.ssh\miraki-key.pem" `
  -PlatformBaseUrl http://localhost:9081 -RoutesDir /opt/apps-routes -KeepRunning
```

La orden hace todo: descarga el artefacto, lo despliega **sin publicar ningún puerto**, escribe su ruta,
recarga el ingreso, **verifica antes de registrar** y registra la URL. Al terminar imprime dos líneas:

```text
Deployment URL: http://34.239.56.54:8080/apps/mi-api
deployment id: <uuid>
```

El `-KeepRunning` es importante: sin él, el despliegue se desmonta al terminar el script.

### Paso 7 — Comprobar lo que ve el teléfono

```bash
curl -s -b $JAR -H "$U" $A/projects/$PROJECT_ID/deployments
```

Tiene que aparecer tu despliegue con `"status":"REGISTERED"` y una `baseUrl` que empiece con
`http://34.239.56.54:8080/apps/`. Eso es exactamente lo que la app lee para saber contra quién hablar.

### Paso 8 — El teléfono

La app resuelve el backend por el registro y ejecuta contra él; su asistente es **local**: un proceso llama.cpp
dentro del propio teléfono (`mobile/lib/ai/llama_child_process.dart`), con un intérprete determinista que valida
lo que el modelo propone. La IA local no necesita la nube para responder.

Falta un paso de compilación, porque el APK instalado apunta a `localhost:8081`:

```bash
flutter build apk --dart-define=platformBaseUrl=http://34.239.56.54:8080
```

Con ese APK, el teléfono alcanza la plataforma por internet y los datos van directos a
`http://34.239.56.54:8080/apps/<slug>`, sin túneles ni cables. Sin recompilar, la alternativa es un túnel desde
tu máquina y un reenvío por USB:

```bash
ssh -f -N -i ~/.ssh/miraki-key.pem -L 9081:localhost:8081 ubuntu@34.239.56.54
adb reverse tcp:8081 tcp:9081     # la plataforma
# los datos no necesitan túnel: el ingreso es público
```

## 4. Operación diaria

| Quiero | Comando |
| --- | --- |
| Apagar sin perder nada | `aws ec2 stop-instances --instance-ids i-056df11455cde02f2` |
| Volver a levantarlo | paso 0 de la sección 3 |
| Ver los contenedores | `ssh -i ~/.ssh/miraki-key.pem ubuntu@34.239.56.54 "docker ps"` |
| Ver las rutas activas | `ssh … "ls /opt/apps-routes/"` |
| Republicar el editor | `tools/aws-deploy/deploy-web.ps1 -Ec2Host 34.239.56.54 -Ec2User ubuntu -SshKey <llave>` |
| Retirar un despliegue | mover su `<slug>.caddy` a `/opt/apps-routes/.retired/` y `docker exec platform-ingress-1 caddy reload --config /etc/caddy/Caddyfile` |
| Ver el gasto | `aws freetier get-account-plan-state` |

**Costo**: la instancia es `c7i-flex.large`, unos **$0.079/hora** encendida y unos **$3,60 al mes** detenida
(solo la IPv4 pública). El crédito disponible es de **USD 39,58** y vence el **28 de octubre de 2026**.

## 5. Qué está verificado y qué no

**Verificado en este despliegue**: el editor se sirve en la raíz del ingreso y la API conserva sus caminos; la
plataforma responde por el ingreso; el modelo sirve en CPU a ~19 tokens/s; el asistente resuelve frases
estructuradas en ~0,5 s y frases libres por el modelo; la generación produce un ZIP de 28 archivos; el
artefacto se despliega sin publicar puerto; el CRUD completo funciona por el ingreso; el backend rechaza
cuerpos inválidos con 400; y el registro publica la `baseUrl` que la app consume.

**No verificado todavía**: el recorrido **dentro del teléfono**. No había dispositivo conectado
(`adb devices` vacío), así que la app no se ejecutó: lo comprobado es su contrato HTTP contra el backend
desplegado, no su interfaz. Tampoco hay HTTPS, y la plataforma conserva su inicio de sesión de demostración
detrás del mismo puerto público.

**Pendiente de limpieza**: ninguno en el registro. El despliegue viejo `vergen-090313`, que apuntaba a
`http://localhost:8100`, se eliminó del registro y su contenedor se retiró de la instancia, así que la app lista
solo el despliegue del ingreso.

## 6. Si algo falla

| Síntoma | Causa más probable | Qué hacer |
| --- | --- | --- |
| El SSH deja de responder | Tu IP pública cambió y la regla del Security Group apunta a la vieja | Autorizar la IP actual en el puerto 22 del grupo `sg-08bcdaef51b6eb509` |
| `502` en `/apps/<slug>/…` | La app todavía no tomó su puerto | Esperar unos segundos; el deployer ya reintenta solo |
| `404` con cuerpo de Spring | La ruta pedida no existe (slug o ruta de entidad equivocados) | Pedir el descriptor y usar las rutas que declara |
| `400 VALIDATION_ERROR` | Falta un campo obligatorio en el cuerpo | Revisar `required` en el descriptor |
| `403` al pedir una propuesta | Sesión caducada (la plataforma se reinició) | Volver a iniciar sesión |
| La generación falla | Se usó `revision 0` | Usar la revisión más alta de `/versions` |
| El teléfono no encuentra despliegues | Su APK apunta a `localhost:8081` y no hay túnel | Recompilar con `--dart-define`, o levantar túnel y `adb reverse` |
| La raíz del ingreso devuelve `404` | `/opt/web` quedó vacío (transferencia interrumpida) | Republicar el editor: el deployer vuelve a copiar el artefacto |
| El ingreso no arranca | Falta la red `apps-net` | `ssh … "docker network create apps-net"` y volver a levantar el stack |
