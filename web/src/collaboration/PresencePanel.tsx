import type { PresencePerson } from '../api/types';

interface PresencePanelProps {
  entries: PresencePerson[];
  userId: string | null;
}

export default function PresencePanel({ entries, userId }: PresencePanelProps) {
  return (
    <section className="surface presence-panel" aria-labelledby="presence-title">
      <div className="section-heading">
        <div>
          <p className="eyebrow">Live collaboration</p>
          <h2 id="presence-title">In this diagram</h2>
        </div>
        <span className="count-chip">{entries.length}</span>
      </div>

      {entries.length === 0 ? (
        <p className="quiet-state presence-empty">No one is in the diagram right now.</p>
      ) : (
        <ul className="presence-list">
          {entries.map((person) => {
            const isReader = person.userId === userId;
            return (
              <li
                className={`presence-row${isReader ? ' presence-row--reader' : ''}`}
                data-current-reader={isReader ? 'true' : undefined}
                key={person.userId}
              >
                <span
                  aria-label={`${person.displayName}'s color`}
                  className="presence-swatch"
                  role="img"
                  style={{ backgroundColor: person.color }}
                />
                <div className="presence-copy">
                  <h3>{person.displayName}</h3>
                  <p>{isReader ? 'You' : 'In the diagram'}</p>
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
