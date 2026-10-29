/**
 * Spanish text for API errors. The server keeps its messages as a developer-facing contract, so the UI owns the
 * wording: a message the server already wrote in Spanish is kept because it is the most precise, and an English one
 * is replaced by the text for its error code.
 */

const SPANISH_TEXT_PATTERN = /[áéíóúñ¿¡]|\b(?:el|la|los|las|del|una|que|para|hay|se|sin|con)\b/i;

/** Codes whose message is content for the user (such as the assistant's clarifying question), never an error text. */
const PASSTHROUGH_CODES = new Set(['NO_PROPOSAL']);

const GENERAL_MESSAGE = 'No se pudo completar la operación. Inténtalo de nuevo.';

/** Codes whose single meaning allows a precise Spanish text. */
const SPECIFIC_MESSAGES: Record<string, string> = {
  AI_QUEUE_FULL: 'La cola de la IA está llena. Inténtalo de nuevo en unos segundos.',
  AI_REQUEST_FAILED: 'No se pudo enviar el pedido a la IA. Inténtalo de nuevo.',
  AI_REQUEST_IN_FLIGHT: 'Ya hay un pedido a la IA en curso. Espera a que termine.',
  AI_REQUEST_RUNNING: 'La IA ya empezó a responder y no se puede interrumpir.',
  ATTACHMENT_TOO_LARGE: 'El archivo adjunto es demasiado grande.',
  BACKEND_CONTRACT_UNAVAILABLE: 'El contrato del backend generado no está disponible.',
  CHAT_ID_CONFLICT: 'Ese identificador ya se usó con otro contenido.',
  CHAT_NOT_FOUND: 'El chat no está disponible.',
  CONTEXT_OVERFLOW: 'El pedido es demasiado grande para la IA. Acota la pregunta e inténtalo de nuevo.',
  CONTRACT_MISMATCH: 'El contrato del backend generado no coincide.',
  DEPLOYMENT_ALREADY_ACTIVE: 'Este destino ya tiene un despliegue activo. Detenlo primero.',
  DEPLOYMENT_ALREADY_CLAIMED: 'Este despliegue ya no está pendiente.',
  DEPLOYMENT_NOT_FOUND: 'No se encontró el despliegue.',
  EXPORT_BLOCKED_EDITING: 'La exportación está bloqueada porque alguien está editando el modelo.',
  GUIDE_PROVIDER_FAILED: 'La guía no obtuvo respuesta de la IA local.',
  GUIDE_REQUEST_INVALID: 'No se pudo preparar la consulta a la guía.',
  GUIDE_RESPONSE_INVALID: 'La guía devolvió una respuesta inválida. Inténtalo de nuevo.',
  HTTP_ERROR: 'El servidor respondió con un error. Inténtalo de nuevo.',
  INVALID_CREDENTIALS: 'El usuario o la contraseña son incorrectos.',
  INVALID_ATTACHMENT: 'Adjunta como máximo 3 imágenes JPEG, PNG o WebP.',
  INVALID_CHAT_REQUEST: 'El mensaje del chat no es válido.',
  INVITATION_EXISTS: 'Ya hay una invitación pendiente para este usuario en el proyecto.',
  INVITATION_NOT_FOUND: 'No se encontró la invitación en este proyecto.',
  INVITATION_NOT_PENDING: 'La invitación ya no está pendiente.',
  MEMBER_ALREADY_ACTIVE: 'El usuario ya es integrante activo del proyecto.',
  MEMBER_NOT_ACTIVE: 'El integrante no está activo en el proyecto.',
  MEMBER_NOT_FOUND: 'No se encontró al integrante del proyecto.',
  MEMORY_NOT_ACTIVE: 'Solo se puede revisar una memoria activa.',
  MODEL_INVALID: 'El cambio dejaría el modelo en un estado inválido.',
  NAME_COLLISION: 'Ya existe un elemento con ese nombre en el modelo.',
  MERGE_CONFLICTS: 'La solicitud de fusión tiene conflictos y no se puede fusionar.',
  MERGE_REQUEST_EXISTS: 'Ya hay una solicitud de fusión abierta entre esas ramas.',
  NETWORK_ERROR: 'No se pudo conectar con el servidor. Revisa la conexión e inténtalo de nuevo.',
  NO_FREE_PORT_PAIR: 'No hay puertos libres para un nuevo despliegue.',
  OPERATION_BODY_TOO_LARGE: 'La operación es demasiado grande.',
  PROPOSAL_DECIDED: 'Esta sugerencia ya fue decidida.',
  PROVIDER_DISABLED: 'La IA no está habilitada en este servidor.',
  RUN_NOT_COMPLETE: 'Solo se puede desplegar una generación que terminó con su archivo.',
  RUN_NOT_FOUND: 'No se encontró la generación.',
  RUN_NOT_IN_PROJECT: 'La generación no pertenece a este proyecto.',
  UNAUTHENTICATED: 'Tu sesión terminó. Inicia sesión de nuevo.',
  USERNAME_TAKEN: 'Ese nombre de usuario ya está en uso. Elige otro.',
  USER_NOT_FOUND: 'No se encontró la cuenta de usuario.',
  WORKING_COPY_CLEAN: 'No hay cambios pendientes para confirmar.',
  WORKING_COPY_DIRTY: 'Confirma o descarta los cambios pendientes antes de cambiar de rama.',
};

/** Codes that cover many situations, so their Spanish text stays general. */
const GENERIC_MESSAGES: Record<string, string> = {
  CONFLICT: 'El cambio entra en conflicto con la versión actual. Recarga e inténtalo de nuevo.',
  FORBIDDEN: 'No tienes permiso para realizar esta acción.',
  INTERNAL_ERROR: 'Ocurrió un error inesperado en el servidor. Inténtalo de nuevo.',
  INVALID_PAYLOAD: 'Revisa los datos ingresados e inténtalo de nuevo.',
  NOT_FOUND: 'No se encontró lo que buscabas.',
  PRECONDITION_FAILED: 'La operación no se pudo aplicar en el estado actual del modelo. Recarga e inténtalo de nuevo.',
  REJECTED: 'El servidor rechazó la operación.',
};

export function isSpanishText(text: string): boolean {
  return SPANISH_TEXT_PATTERN.test(text);
}

export function localizedErrorMessage(code: string, message: string): string {
  const trimmed = message.trim();
  if (trimmed && (PASSTHROUGH_CODES.has(code) || isSpanishText(trimmed))) {
    return trimmed;
  }
  return SPECIFIC_MESSAGES[code] ?? GENERIC_MESSAGES[code] ?? GENERAL_MESSAGE;
}

/** Text for a command the model rejected, taken from its first diagnostic. */
export function commandRejectionMessage(diagnostics: ReadonlyArray<{ code: string; message: string }> | undefined): string {
  const first = diagnostics?.[0];
  return first ? localizedErrorMessage(first.code, first.message) : 'El modelo rechazó el cambio.';
}
