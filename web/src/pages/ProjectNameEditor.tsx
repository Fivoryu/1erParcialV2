import { useState, type FormEvent } from 'react';

export const PROJECT_NAME_MAX_LENGTH = 200;

/** Returns the validation message for a project name, or null when it is usable. */
export function projectNameError(name: string): string | null {
  return name.trim() ? null : 'Escribe un nombre de proyecto para continuar.';
}

interface ProjectNameEditorProps {
  name: string;
  onRename: (name: string) => Promise<void>;
}

/** Shows the project title and lets the user rename the project in place. */
export default function ProjectNameEditor({ name, onRename }: ProjectNameEditorProps) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(name);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  function startEditing(): void {
    setDraft(name);
    setError(null);
    setEditing(true);
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault();
    const validation = projectNameError(draft);
    if (validation) {
      setError(validation);
      return;
    }
    if (draft.trim() === name) {
      setEditing(false);
      return;
    }
    setSaving(true);
    try {
      await onRename(draft.trim());
      setEditing(false);
    } finally {
      setSaving(false);
    }
  }

  if (!editing) {
    return (
      <div className="project-name">
        <h1 id="project-title">{name}</h1>
        <button
          aria-label="Renombrar proyecto"
          className="text-button"
          onClick={startEditing}
          title="Renombrar proyecto"
          type="button"
        >
          Renombrar
        </button>
      </div>
    );
  }

  return (
    <form className="project-name project-name-form" onSubmit={(event) => void handleSubmit(event)}>
      <label className="sr-only" htmlFor="project-rename-input">Nombre del proyecto</label>
      <input
        aria-describedby={error ? 'project-rename-error' : undefined}
        autoComplete="off"
        autoFocus
        id="project-rename-input"
        maxLength={PROJECT_NAME_MAX_LENGTH}
        onChange={(event) => setDraft(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === 'Escape') {
            setEditing(false);
          }
        }}
        value={draft}
      />
      <button className="button button-primary button-compact" disabled={saving} type="submit">
        {saving ? 'Guardando…' : 'Guardar'}
      </button>
      <button className="text-button" disabled={saving} onClick={() => setEditing(false)} type="button">
        Cancelar
      </button>
      {error && <span className="project-name-error" id="project-rename-error" role="alert">{error}</span>}
    </form>
  );
}
