import { useState } from 'react';

import type { ApiClient } from '../api/client';
import type { EditorSectionId } from '../pages/editorSections';
import HelpGuidePanel from './HelpGuidePanel';
import {
  buildHelpGuideRequest,
  classifyHelpGuideError,
  parseHelpGuideResponse,
  type HelpGuidePanelState,
} from './helpGuide';

export type HelpGuideProps = {
  api: Pick<ApiClient, 'askHelpGuide'>;
  openSection: EditorSectionId | null;
  onNavigateToSection: (section: EditorSectionId) => void;
};

export default function HelpGuide({ api, openSection, onNavigateToSection }: HelpGuideProps) {
  const [isOpen, setIsOpen] = useState(false);
  const [draft, setDraft] = useState('');
  const [state, setState] = useState<HelpGuidePanelState>({ kind: 'idle' });

  async function ask(question: string): Promise<void> {
    if (state.kind === 'loading') {
      return;
    }
    const request = buildHelpGuideRequest(question, openSection);
    setState({ kind: 'loading' });
    try {
      const response = await api.askHelpGuide(request);
      setState({
        kind: 'answered',
        response: parseHelpGuideResponse(response, request.topics.map((topic) => topic.slug)),
      });
    } catch (error: unknown) {
      setState(classifyHelpGuideError(error));
    }
  }

  return (
    <HelpGuidePanel
      isOpen={isOpen}
      draft={draft}
      state={state}
      openSection={openSection}
      onToggle={() => setIsOpen((current) => !current)}
      onClose={() => setIsOpen(false)}
      onDraftChange={setDraft}
      onSubmit={(question) => void ask(question)}
      onNavigateToSection={onNavigateToSection}
    />
  );
}
