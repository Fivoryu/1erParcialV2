import { describe, expect, it } from 'vitest';
import { ApiTransportError } from './client';
import { commandRejectionMessage, localizedErrorMessage } from './errorMessages';

describe('localizedErrorMessage', () => {
  it('keeps a message the server already wrote in Spanish', () => {
    expect(localizedErrorMessage('NOT_FOUND', 'No se encontró el proyecto.')).toBe('No se encontró el proyecto.');
    expect(localizedErrorMessage('NOTHING_TO_UNDO', 'No hay acciones para deshacer.')).toBe('No hay acciones para deshacer.');
  });

  it('translates an English message by its specific code', () => {
    expect(localizedErrorMessage('MERGE_CONFLICTS', 'This merge request has conflicts and cannot be merged.'))
      .toBe('La solicitud de fusión tiene conflictos y no se puede fusionar.');
    expect(localizedErrorMessage('WORKING_COPY_DIRTY', 'Commit or discard working-copy changes before checking out another branch.'))
      .toBe('Confirma o descarta los cambios pendientes antes de cambiar de rama.');
  });

  it('translates the missing photo of a suggestion from a photo', () => {
    expect(localizedErrorMessage('NO_IMAGE', 'That message has no photo to turn into a suggestion.'))
      .toBe('Ese mensaje no tiene una foto para convertir en sugerencia.');
  });

  it('translates the merge approval codes', () => {
    expect(localizedErrorMessage('APPROVAL_REQUIRED', 'Another editor or owner must approve this merge request before it can be merged.'))
      .toBe('Necesita la aprobación de otro integrante antes de fusionarse.');
    expect(localizedErrorMessage('MERGE_REQUEST_NOT_OPEN', 'Only an OPEN merge request can be approved.'))
      .toBe('La solicitud de fusión ya no está abierta.');
  });

  it('falls back to a Spanish text for a generic code with an English message', () => {
    expect(localizedErrorMessage('INVALID_PAYLOAD', 'Branch name must contain 1 to 120 characters.'))
      .toBe('Revisa los datos ingresados e inténtalo de nuevo.');
    expect(localizedErrorMessage('NOT_FOUND', 'Resource was not found.')).toBe('No se encontró lo que buscabas.');
    expect(localizedErrorMessage('NETWORK_ERROR', 'Failed to fetch'))
      .toBe('No se pudo conectar con el servidor. Revisa la conexión e inténtalo de nuevo.');
  });

  it('uses a general Spanish text for an unknown code with an English message', () => {
    expect(localizedErrorMessage('SOMETHING_NEW', 'Something went wrong.'))
      .toBe('No se pudo completar la operación. Inténtalo de nuevo.');
  });
});

describe('ApiTransportError', () => {
  it('exposes the Spanish message and keeps the server text for diagnosis', () => {
    const error = new ApiTransportError('INVITATION_EXISTS', 'A pending invitation already exists for this user and project.', [], null, 409);

    expect(error.message).toBe('Ya hay una invitación pendiente para este usuario en el proyecto.');
    expect(error.serverMessage).toBe('A pending invitation already exists for this user and project.');
    expect(error.code).toBe('INVITATION_EXISTS');
  });
});

describe('commandRejectionMessage', () => {
  it('explains a rejected command in Spanish from its first diagnostic', () => {
    expect(commandRejectionMessage([{ code: 'NAME_COLLISION', message: "Classifier name 'Order' already exists." }]))
      .toBe('Ya existe un elemento con ese nombre en el modelo.');
    expect(commandRejectionMessage([{ code: 'UNKNOWN_TARGET', message: 'El destino de la operación ya no existe en el diagrama.' }]))
      .toBe('El destino de la operación ya no existe en el diagrama.');
  });

  it('uses a Spanish default when the server gave no diagnostic', () => {
    expect(commandRejectionMessage([])).toBe('El modelo rechazó el cambio.');
    expect(commandRejectionMessage(undefined)).toBe('El modelo rechazó el cambio.');
  });
});
