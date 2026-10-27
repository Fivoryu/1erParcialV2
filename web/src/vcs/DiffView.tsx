import type { VcsCommitAuthor, VcsElementChange } from './api';
import { authorRoleLabel, commitChangeLabel, diffFieldLabel, elementTypeLabel } from './presentation';

const CHANGE_MARKERS: Record<string, string> = {
  ADDED: '+',
  REMOVED: '−',
  MODIFIED: '~',
};

export interface AuthorsListProps {
  authors: VcsCommitAuthor[];
}

/** The people credited on a commit or a merge request, each with what they did. */
export function AuthorsList({ authors }: AuthorsListProps) {
  if (authors.length === 0) {
    return null;
  }
  return (
    <ul className="vcs-author-list" aria-label="Autores">
      {authors.map((author) => (
        <li key={`${author.userId}-${author.role}`}>
          <strong>{author.displayName}</strong>
          <span>{authorRoleLabel(author.role)}</span>
        </li>
      ))}
    </ul>
  );
}

export interface DiffViewProps {
  changes: VcsElementChange[];
}

/** GitHub-like diff: one block per changed element, one line per changed field with its before and after value. */
export function DiffView({ changes }: DiffViewProps) {
  if (changes.length === 0) {
    return <p className="quiet-state">Sin diferencias en los elementos.</p>;
  }
  return (
    <ul className="vcs-diff" aria-label="Diferencias">
      {changes.map((change) => (
        <li
          className={`vcs-diff-item vcs-diff-${change.changeKind.toLowerCase()}`}
          key={`${change.targetType}-${change.targetId}`}
        >
          <div className="vcs-diff-head">
            <span className="vcs-diff-marker" aria-hidden="true">{CHANGE_MARKERS[change.changeKind] ?? '~'}</span>
            <strong>{change.targetName}</strong>
            <span className="vcs-diff-type">
              {elementTypeLabel(change.targetType)}
              {change.ownerName ? ` de ${change.ownerName}` : ''}
            </span>
            <span className="vcs-diff-kind">{commitChangeLabel(change.changeKind)}</span>
          </div>
          {change.fields.length > 0 && (
            <ul className="vcs-diff-fields">
              {change.fields.map((field) => (
                <li key={field.field}>
                  <span className="vcs-diff-field">{diffFieldLabel(field.field)}</span>
                  {field.before !== null && <del>{field.before}</del>}
                  {field.after !== null && <ins>{field.after}</ins>}
                </li>
              ))}
            </ul>
          )}
        </li>
      ))}
    </ul>
  );
}
