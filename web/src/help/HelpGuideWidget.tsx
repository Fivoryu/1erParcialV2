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
  onStartTour: (slug: string) => void;
  onOpenChange: (open: boolean) => void;
};

export default function HelpGuide({ api, openSection, onNavigateToSection, onStartTour, onOpenChange }: HelpGuideProps) {
  const [isOpen, setIsOpen] = useState(false);
  const [draft, setDraft] = useState('');
  const [state, setState] = useState<HelpGuidePanelState>({ kind: 'idle' });

  function changeOpen(open: boolean): void {
    setIsOpen(open);
    onOpenChange(open);
  }

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
      onToggle={() => changeOpen(!isOpen)}
      onClose={() => changeOpen(false)}
      onDraftChange={setDraft}
      onSubmit={(question) => void ask(question)}
      onNavigateToSection={onNavigateToSection}
      onStartTour={(slug) => {
        changeOpen(false);
        onStartTour(slug);
      }}
    />
  );
}
