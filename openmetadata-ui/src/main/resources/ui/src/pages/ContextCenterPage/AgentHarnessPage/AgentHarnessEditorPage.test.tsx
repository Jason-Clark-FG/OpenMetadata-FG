/*
 *  Copyright 2026 Collate.
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import { AxiosError } from 'axios';
import { ReactNode } from 'react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import {
  AgentHarnessState,
  HarnessKind,
  HarnessStatus,
  HookEvent,
  MemoryReview,
  MemoryVisibility,
  ToolAccessDefault,
  ToolAccessLevel,
} from '../../../generated/api/context/agentHarnessState';
import {
  createPersonaAgentHarness,
  getDefaultAgentHarness,
  getPersonaAgentHarness,
  publishAgentHarness,
  putAgentHarnessDraft,
} from '../../../rest/AgentHarnessAPI';
import AgentHarnessEditorPage from './AgentHarnessEditorPage';

const mockAuth = { isAdminUser: true };

jest.mock('../../../rest/AgentHarnessAPI', () => ({
  createPersonaAgentHarness: jest.fn(),
  getAgentHarnessConnections: jest.fn().mockResolvedValue([]),
  getAgentHarnessGuardrails: jest.fn().mockResolvedValue([
    {
      available: true,
      defaultLevel: 'Block',
      description: 'Deprecated assets are refused',
      event: 'PreToolUse',
      id: 'block-deprecated',
      name: 'Block deprecated assets',
    },
  ]),
  getAgentHarnessSkills: jest.fn().mockResolvedValue([
    {
      defaultEnabled: true,
      description: 'Look up context before answering',
      name: 'find-context',
    },
  ]),
  getAgentHarnessSubAgents: jest.fn().mockResolvedValue([]),
  getDefaultAgentHarness: jest.fn(),
  getPersonaAgentHarness: jest.fn(),
  previewAgentHarness: jest.fn().mockReturnValue(new Promise(() => undefined)),
  publishAgentHarness: jest.fn(),
  putAgentHarnessDraft: jest.fn(),
  searchHarnessSource: jest.fn().mockResolvedValue([]),
  setAgentHarnessEnabled: jest.fn(),
}));

jest.mock('../../../hooks/authHooks', () => ({
  useAuth: () => mockAuth,
}));

jest.mock(
  '../../../components/ContextCenter/ContextCenterHeader/ContextCenterHeader.component',
  () =>
    ({ actionsSlot, title }: { actionsSlot?: ReactNode; title: string }) =>
      (
        <div data-testid="context-center-header">
          <span>{title}</span>
          {actionsSlot}
        </div>
      )
);

jest.mock(
  '../../../components/ContextCenter/AgentHarness/ContextRuleEditor/ContextRuleEditor.component',
  () => ({ ContextRuleEditor: () => null })
);

jest.mock(
  '../../../components/common/DocumentTitle/DocumentTitle',
  () => () => null
);

const stateOf = (overrides: Partial<AgentHarnessState>): AgentHarnessState => ({
  draftRevision: 2,
  published: {
    context: { cacheTtlMinutes: 30, characterBudget: 400000, rules: [] },
    enabled: true,
    id: 'analyst-harness',
    kind: HarnessKind.Persona,
    name: 'Analyst',
    perTurnTokenBudget: 20000,
    persona: { displayName: 'Data Analyst', id: 'p1', type: 'persona' },
    version: 1.2,
  },
  resolved: {
    cacheTtlMinutes: 30,
    characterBudget: 400000,
    events: [
      {
        enabled: true,
        id: HookEvent.SessionStart,
        inherited: true,
        maxTokenBudget: 6000,
        tokenBudget: 6000,
      },
    ],
    guardrails: [],
    kind: HarnessKind.Persona,
    memory: {
      capture: true,
      dedup: true,
      linkAssets: true,
      review: MemoryReview.StewardReview,
      visibility: MemoryVisibility.Private,
    },
    memoryInherited: true,
    perTurnTokenBudget: 20000,
    rules: [],
    skills: [],
  },
  status: HarnessStatus.Published,
  ...overrides,
});

const renderEditor = (path: string) =>
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route
          element={<AgentHarnessEditorPage />}
          path="/context-center/agent-harness/persona/:fqn"
        />
      </Routes>
    </MemoryRouter>
  );

describe('AgentHarnessEditorPage', () => {
  beforeEach(() => {
    mockAuth.isAdminUser = true;
    (getDefaultAgentHarness as jest.Mock).mockResolvedValue(
      stateOf({
        published: {
          ...stateOf({}).published,
          kind: HarnessKind.Default,
          version: 1.3,
        },
      })
    );
    (getPersonaAgentHarness as jest.Mock).mockResolvedValue(stateOf({}));
  });

  it('opens the persona harness on the tab the link names', async () => {
    renderEditor('/context-center/agent-harness/persona/Analyst?tab=delivery');

    expect(await screen.findByTestId('delivery-tab')).toBeInTheDocument();
    expect(screen.getByTestId('context-center-header')).toHaveTextContent(
      'Data Analyst'
    );
    expect(screen.getByTestId('harness-version')).toHaveTextContent(/^v1\.2$/);
    expect(screen.getByTestId('harness-save-state')).toHaveTextContent(
      'label.published-version'
    );
    expect(getPersonaAgentHarness).toHaveBeenCalledWith('Analyst');
    expect(
      await screen.findByText('label.company-default-version')
    ).toBeInTheDocument();
  });

  it('puts the preview rail under the tabs, beside the tab content', async () => {
    renderEditor('/context-center/agent-harness/persona/Analyst');

    const body = await screen.findByTestId('harness-tab-body');
    const tabs = screen.getByRole('tablist');

    expect(
      within(body).getByTestId('compiled-context-rail')
    ).toBeInTheDocument();
    expect(body).not.toContainElement(tabs);
    expect(
      tabs.compareDocumentPosition(body) & Node.DOCUMENT_POSITION_FOLLOWING
    ).toBeTruthy();
  });

  it('allows publishing only when there is a draft', async () => {
    (getPersonaAgentHarness as jest.Mock).mockResolvedValue(
      stateOf({
        draft: {
          context: { cacheTtlMinutes: 30, characterBudget: 400000 },
          perTurnTokenBudget: 18000,
        },
        status: HarnessStatus.Draft,
      })
    );
    (publishAgentHarness as jest.Mock).mockResolvedValue(stateOf({}));
    renderEditor('/context-center/agent-harness/persona/Analyst');

    const publish = await screen.findByTestId('publish-harness');

    expect(publish).not.toBeDisabled();
    expect(screen.getByTestId('harness-save-state')).toHaveTextContent(
      'message.agent-harness-draft-unpublished'
    );

    fireEvent.click(publish);

    await waitFor(() =>
      expect(publishAgentHarness).toHaveBeenCalledWith('analyst-harness', 2)
    );
    await waitFor(() => expect(publish).toBeDisabled());
  });

  it('offers to create a harness for a persona that uses the default', async () => {
    (getPersonaAgentHarness as jest.Mock)
      .mockRejectedValueOnce(
        Object.assign(new AxiosError('missing'), {
          response: { data: {}, status: 404 },
        })
      )
      .mockResolvedValue(stateOf({ status: HarnessStatus.NotConfigured }));
    (createPersonaAgentHarness as jest.Mock).mockResolvedValue({});
    renderEditor('/context-center/agent-harness/persona/Analyst');

    fireEvent.click(await screen.findByText('label.new-persona-harness'));

    expect(await screen.findByTestId('context-tab')).toBeInTheDocument();
    expect(createPersonaAgentHarness).toHaveBeenCalledWith('Analyst');
  });

  it('lists the registered skills on the Instructions tab', async () => {
    (getPersonaAgentHarness as jest.Mock).mockResolvedValue(
      stateOf({
        resolved: {
          ...stateOf({}).resolved,
          skills: [{ enabled: true, inherited: true, name: 'find-context' }],
        },
      })
    );
    renderEditor(
      '/context-center/agent-harness/persona/Analyst?tab=instructions'
    );

    expect(await screen.findByTestId('skill-find-context')).toHaveTextContent(
      'label.from-default'
    );
    expect(screen.getByTestId('persona-prompt')).toBeInTheDocument();
  });

  it('holds back a draft whose MCP access loosens the default', async () => {
    (getPersonaAgentHarness as jest.Mock).mockResolvedValue(
      stateOf({
        draft: {
          context: { cacheTtlMinutes: 30, characterBudget: 400000 },
          perTurnTokenBudget: 20000,
          toolAccess: {
            default: ToolAccessDefault.Allow,
            servers: [{ level: ToolAccessLevel.Allow, match: 'github' }],
          },
        },
        resolved: {
          ...stateOf({}).resolved,
          inheritedToolAccess: {
            default: ToolAccessDefault.Allow,
            servers: [{ level: ToolAccessLevel.Block, match: 'github' }],
          },
        },
        status: HarnessStatus.Draft,
      })
    );
    (putAgentHarnessDraft as jest.Mock).mockResolvedValue(
      stateOf({ status: HarnessStatus.Draft })
    );
    renderEditor(
      '/context-center/agent-harness/persona/Analyst?tab=guardrails'
    );

    expect(
      await screen.findByText('message.agent-harness-mcp-below-default')
    ).toBeInTheDocument();
    expect(screen.getByTestId('publish-harness')).toBeDisabled();

    fireEvent.click(screen.getByTestId('mcp-server-level-0-Block'));

    await waitFor(
      () =>
        expect(putAgentHarnessDraft).toHaveBeenCalledWith(
          'analyst-harness',
          2,
          expect.objectContaining({
            toolAccess: {
              default: ToolAccessDefault.Allow,
              servers: [{ level: ToolAccessLevel.Block, match: 'github' }],
            },
          })
        ),
      { timeout: 3000 }
    );
  });

  it('shows nothing of the harness to a non-admin', async () => {
    mockAuth.isAdminUser = false;
    renderEditor('/context-center/agent-harness/persona/Analyst');

    expect(await screen.findByText('label.access-denied')).toBeInTheDocument();
    expect(screen.queryByTestId('publish-harness')).not.toBeInTheDocument();
  });
});
