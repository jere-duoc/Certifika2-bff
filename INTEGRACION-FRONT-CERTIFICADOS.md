# Certifika2 · Integración Frontend ↔ Certificados (AWS Kinesis + Firehose)

Guía para el desarrollador Angular que integra la generación de certificados.
Todo lo que el front necesita pasa por el **BFF**; el front **nunca** habla directo con Kinesis, Firehose ni S3.

---

## 1. Cómo funciona (vista del front)

```
Angular ──HTTP──► BFF :3000 ──► API Manager :8080 ──► ms-cursos :8082 ──► Kinesis (stream de solicitudes)
                                                                              │
Angular ◄──HTTP── BFF ◄── API Manager ◄── ms-usuarios :8081 ◄── consume ───────┘
                                              │ genera PDF/PNG → S3
                                              └─► Firehose → S3 (analítica, no afecta al front)
```

Lo importante para el front: **la generación es asíncrona**.

1. El front pide el certificado → el backend responde **`202 Accepted`** de inmediato (todavía no existe el archivo).
2. El evento viaja por Kinesis y ms-usuarios genera el archivo (normalmente **2 a 5 segundos**).
3. El front consulta la lista de certificados hasta que aparece el nuevo, y recién ahí lo descarga.

---

## 2. Configuración

| Variable | Local | Descripción |
|---|---|---|
| URL del BFF | `http://localhost:3000` | Único backend que consume el front |
| Prefijo de rutas | `/bff` | Todas las rutas de esta guía cuelgan de aquí |
| Autenticación | `Authorization: Bearer <JWT>` | El mismo JWT que entrega el IDaaS en el login |

### CORS: usar proxy de Angular

El BFF **no tiene CORS configurado**. En desarrollo usa el proxy de Angular para que el navegador vea todo como el mismo origen (además así se puede leer el header `Content-Disposition` de las descargas).

`proxy.conf.json`:

```json
{
  "/bff": {
    "target": "http://localhost:3000",
    "secure": false,
    "changeOrigin": true
  }
}
```

`angular.json` → `serve.options.proxyConfig: "proxy.conf.json"` (o `ng serve --proxy-config proxy.conf.json`).

Con esto, en `environment.ts`:

```ts
export const environment = {
  production: false,
  bffUrl: '/bff'
};
```

---

## 3. Endpoints

Todos requieren `Authorization: Bearer <JWT>` salvo la verificación pública.

| # | Método | Ruta | Uso | Respuesta OK |
|---|---|---|---|---|
| 1 | `POST` | `/bff/cursos/{idCurso}/certificado?formato=PDF\|PNG` | El estudiante termina el curso y pide su certificado | `202` JSON |
| 2 | `POST` | `/bff/cursos/{idCurso}/estudiantes/{idUsuario}/certificado?formato=PDF\|PNG` | El **profesor del curso** emite el certificado de un alumno | `202` JSON |
| 3 | `GET` | `/bff/certificados` | Lista los certificados del usuario logueado | `200` JSON array |
| 4 | `GET` | `/bff/certificados/{idCertificado}/descarga` | Descarga el archivo | `200` binario (PDF o PNG) |
| 5 | `GET` | `/bff/certificados/verificar/{codigo}` | Verificación **pública** (sin login) | `200` JSON |

`formato` es opcional; por defecto `PDF`. Valores válidos: `PDF` o `PNG` (en mayúsculas).

### 3.1 Solicitar certificado (estudiante)

```http
POST /bff/cursos/1/certificado?formato=PDF
Authorization: Bearer eyJhbGciOi...
```

Respuesta `202 Accepted`:

```json
{
  "eventId": "7b1e5a7c-3f0a-4d0e-9a51-2c8a9d4b6f10",
  "idUsuario": 12,
  "idCurso": 1,
  "formato": "PDF",
  "estado": "EN_PROCESO",
  "mensaje": "Certificado en generación. Consúltelo en /api/certificados"
}
```

> Requisito: el usuario debe estar **inscrito** en el curso. Si no, responde `409`.
> Pedir de nuevo el mismo curso y formato vuelve a responder `202`, pero **no** crea un segundo certificado: se conserva el que ya existe.

### 3.2 Emitir certificado (profesor)

```http
POST /bff/cursos/1/estudiantes/12/certificado?formato=PNG
Authorization: Bearer <JWT del profesor>
```

Misma respuesta `202`. Solo funciona si el usuario logueado es el **profesor creador** del curso (si no, `403`).

### 3.3 Mis certificados

```http
GET /bff/certificados
Authorization: Bearer eyJhbGciOi...
```

Respuesta `200`, ordenada del más reciente al más antiguo:

```json
[
  {
    "idCertificado": 3,
    "codigoVerificacion": "e4c2b1a0-9f8e-4d7c-b6a5-0123456789ab",
    "idUsuario": 12,
    "nombreUsuario": "Jeremias Silva",
    "idCurso": 1,
    "nombreCurso": "Spring Boot desde cero",
    "nivelCurso": "Básico",
    "formato": "PDF",
    "tamanoBytes": 2841,
    "fechaCompletado": "2026-11-25T04:10:12.345Z",
    "fechaEmision": "2026-11-25T04:10:15.012Z",
    "urlDescarga": "/api/certificados/3/descarga"
  }
]
```

> ⚠️ `urlDescarga` es la ruta **interna** de ms-usuarios. Desde el front usa siempre
> `/bff/certificados/{idCertificado}/descarga`.
> Las fechas vienen en ISO-8601 UTC; formatéalas con `DatePipe` en la zona local.

### 3.4 Descargar

```http
GET /bff/certificados/3/descarga
Authorization: Bearer eyJhbGciOi...
```

Respuesta `200` binaria:

- `Content-Type: application/pdf` o `image/png`
- `Content-Disposition: attachment; filename="certificado-Spring_Boot_desde_cero.pdf"`

Si el certificado no existe **o pertenece a otro usuario** → `404`.

### 3.5 Verificar (público)

Pensado para una página pública tipo `/verificar/:codigo` (por ejemplo, un QR o link en el certificado).

```http
GET /bff/certificados/verificar/e4c2b1a0-9f8e-4d7c-b6a5-0123456789ab
```

```json
{ "valido": true, "codigoVerificacion": "e4c2...", "nombreTitular": "Jeremias Silva",
  "nombreCurso": "Spring Boot desde cero", "fechaEmision": "2026-11-25T04:10:15.012Z" }
```

Código inexistente → `200` con `"valido": false` y el resto en `null`.

---

## 4. Errores

Los errores de ms-cursos vienen en formato **ProblemDetail** (RFC 7807):

```json
{ "type": "about:blank", "title": "Conflict", "status": 409,
  "detail": "El usuario no está inscrito en el curso 1", "instance": "/api/cursos/1/certificado" }
```

| Código | Cuándo | Qué mostrar |
|---|---|---|
| `401` | Sin token, token vencido o usuario no registrado | Redirigir a login |
| `403` | Profesor que no es dueño del curso | "No tienes permisos sobre este curso" |
| `404` | Curso o certificado inexistente | "No encontrado" |
| `409` | No está inscrito en el curso | "Debes inscribirte antes de certificarte" |
| `429` | Límite de peticiones del API Manager | Reintentar en unos segundos |
| `503` | Kinesis no disponible (no se publicó la solicitud) | "Servicio ocupado, intenta nuevamente" — **se puede reintentar sin riesgo** |
| `502` | El API Manager no alcanzó el microservicio | Error genérico |

Si la solicitud respondió `202` pero el certificado **no aparece después de ~30 s**, el evento probablemente falló y quedó en la DLQ del backend. Muestra un mensaje del tipo "Tu certificado está tardando más de lo normal" y deja un botón para reintentar.

---

## 5. Código Angular de base

### 5.1 Modelos — `certificado.model.ts`

```ts
export type FormatoCertificado = 'PDF' | 'PNG';

export interface SolicitudCertificadoResponse {
  eventId: string;
  idUsuario: number;
  idCurso: number;
  formato: FormatoCertificado;
  estado: 'EN_PROCESO';
  mensaje: string;
}

export interface Certificado {
  idCertificado: number;
  codigoVerificacion: string;
  idUsuario: number;
  nombreUsuario: string;
  idCurso: number;
  nombreCurso: string;
  nivelCurso: string | null;
  formato: FormatoCertificado;
  tamanoBytes: number;
  fechaCompletado: string; // ISO-8601
  fechaEmision: string;    // ISO-8601
  urlDescarga: string;     // ruta interna, no usar desde el front
}

export interface VerificacionCertificado {
  valido: boolean;
  codigoVerificacion: string;
  nombreTitular: string | null;
  nombreCurso: string | null;
  fechaEmision: string | null;
}
```

### 5.2 Servicio — `certificado.service.ts`

El JWT se agrega con el interceptor de auth que ya usa el proyecto (no se repite aquí).

```ts
import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams, HttpResponse } from '@angular/common/http';
import { Observable, timer, throwError } from 'rxjs';
import { filter, map, switchMap, take, timeout } from 'rxjs/operators';
import { environment } from '../../environments/environment';
import {
  Certificado, FormatoCertificado, SolicitudCertificadoResponse, VerificacionCertificado
} from './certificado.model';

@Injectable({ providedIn: 'root' })
export class CertificadoService {
  private http = inject(HttpClient);
  private base = environment.bffUrl;

  solicitar(idCurso: number, formato: FormatoCertificado = 'PDF'): Observable<SolicitudCertificadoResponse> {
    const params = new HttpParams().set('formato', formato);
    return this.http.post<SolicitudCertificadoResponse>(`${this.base}/cursos/${idCurso}/certificado`, null, { params });
  }

  emitirPorProfesor(idCurso: number, idUsuario: number, formato: FormatoCertificado = 'PDF') {
    const params = new HttpParams().set('formato', formato);
    return this.http.post<SolicitudCertificadoResponse>(
      `${this.base}/cursos/${idCurso}/estudiantes/${idUsuario}/certificado`, null, { params });
  }

  listar(): Observable<Certificado[]> {
    return this.http.get<Certificado[]>(`${this.base}/certificados`);
  }

  /**
   * Consulta cada 2 s hasta que aparece el certificado del curso/formato pedido.
   * Corta con error a los 30 s.
   */
  esperarCertificado(idCurso: number, formato: FormatoCertificado): Observable<Certificado> {
    return timer(0, 2000).pipe(
      switchMap(() => this.listar()),
      map(lista => lista.find(c => c.idCurso === idCurso && c.formato === formato)),
      filter((c): c is Certificado => !!c),
      take(1),
      timeout({ first: 30000, with: () => throwError(() => new Error('TIMEOUT_CERTIFICADO')) })
    );
  }

  /** Pide y espera en un solo flujo: útil para el botón "Obtener certificado". */
  solicitarYEsperar(idCurso: number, formato: FormatoCertificado = 'PDF'): Observable<Certificado> {
    return this.solicitar(idCurso, formato).pipe(
      switchMap(() => this.esperarCertificado(idCurso, formato))
    );
  }

  descargar(idCertificado: number): Observable<HttpResponse<Blob>> {
    return this.http.get(`${this.base}/certificados/${idCertificado}/descarga`, {
      responseType: 'blob',
      observe: 'response'
    });
  }

  verificar(codigo: string): Observable<VerificacionCertificado> {
    return this.http.get<VerificacionCertificado>(`${this.base}/certificados/verificar/${codigo}`);
  }
}
```

### 5.3 Guardar el archivo descargado

```ts
guardarArchivo(resp: HttpResponse<Blob>, nombrePorDefecto = 'certificado') {
  const disposition = resp.headers.get('Content-Disposition') ?? '';
  const match = /filename="?([^"]+)"?/.exec(disposition);
  const nombre = match?.[1] ?? nombrePorDefecto;

  const url = URL.createObjectURL(resp.body!);
  const a = document.createElement('a');
  a.href = url;
  a.download = nombre;
  a.click();
  URL.revokeObjectURL(url);
}
```

Para **previsualizar** en vez de descargar:

- PNG: `<img [src]="urlSegura">` con `DomSanitizer.bypassSecurityTrustUrl(URL.createObjectURL(blob))`.
- PDF: `<iframe [src]="urlSegura">` con `bypassSecurityTrustResourceUrl(...)`.

### 5.4 Ejemplo de uso en un componente

```ts
estado: 'idle' | 'generando' | 'listo' | 'error' = 'idle';
certificado?: Certificado;

obtenerCertificado(idCurso: number, formato: FormatoCertificado) {
  this.estado = 'generando';
  this.certificadoService.solicitarYEsperar(idCurso, formato).subscribe({
    next: cert => { this.certificado = cert; this.estado = 'listo'; },
    error: err => {
      this.estado = 'error';
      // err.status 409 → no inscrito; 503 → reintentar; err.message 'TIMEOUT_CERTIFICADO' → tardando más de lo normal
    }
  });
}
```

Recomendaciones de UX:

- Deshabilita el botón mientras `estado === 'generando'` (spinner "Generando tu certificado…").
- Ofrece elegir PDF o PNG; ambos se pueden tener para el mismo curso.
- En "Mis certificados" muestra el `codigoVerificacion` y un link a `/verificar/{codigo}`.

---

## 6. Levantar el backend en local para probar

Requisitos: Docker, Java 17, Maven, PostgreSQL local (o variables `RDS_*`).

```bash
# 1. Infraestructura AWS simulada (Kinesis, Firehose, S3) — repo Certifika2-Microservicio-Cursos
docker compose -f infra/docker-compose.yml up -d
docker logs -f certifika2-localstack      # esperar "[init] Recursos creados"

# 2. Microservicios (cada uno en su repo)
mvn spring-boot:run   # ms-usuarios  :8081
mvn spring-boot:run   # ms-cursos    :8082
mvn spring-boot:run   # api-manager  :8080
mvn spring-boot:run   # bff          :3000
```

Para tener un curso donde probar, inscríbete primero (este endpoint aún no está en el BFF, se llama vía API Manager):

```http
POST http://localhost:8080/api/proxy/cursos/{idCurso}/inscribir
Authorization: Bearer <JWT>
```

Ver que el evento llegó a Kinesis y que el archivo quedó en S3:

```bash
docker exec certifika2-localstack awslocal s3 ls s3://certifika2-certificados --recursive
```

Más detalle de la infraestructura: `infra/README.md` en el repo de ms-cursos.

---

## 7. Checklist de integración

- [ ] Proxy de Angular configurado hacia el BFF (`/bff`).
- [ ] Interceptor agrega `Authorization: Bearer <JWT>` a las rutas `/bff/**` (excepto `/bff/certificados/verificar/**`).
- [ ] Botón "Obtener certificado" en el detalle del curso, con selector PDF/PNG y estado "generando".
- [ ] Vista "Mis certificados" con descarga y previsualización.
- [ ] Página pública `/verificar/:codigo`.
- [ ] Manejo de `401`, `409`, `503` y del timeout de 30 s.
