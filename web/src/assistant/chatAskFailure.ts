import { ApiTransportError } from '../api/client';

export const PROVIDER_DISABLED_MESSAGE =
  'La IA local no está habilitada en esta implementación. Comunícate con quien la administra.';

export function chatAskErrorMessage(error: unknown, fallback: (cause: unknown) => string): string {
  if (error instanceof ApiTransportError
    && (error.code === 'PROVIDER_DISABLED'
      || (error.status === 503 && error.code !== 'HTTP_ERROR'))) {
    return PROVIDER_DISABLED_MESSAGE;
  }
  return fallback(error);
}
