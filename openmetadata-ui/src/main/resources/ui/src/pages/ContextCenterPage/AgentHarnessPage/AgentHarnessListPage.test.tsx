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
import { ReactNode } from 'react';
import { MemoryRouter } from 'react-router-dom';
import {
  AgentHarnessPersonaSummary,
  CacheState,
  HarnessStatus,
  HookEvent,
} from '../../../generated/api/context/agentHarnessPersonaSummary';
import { AgentHarnessState } from '../../../generated/api/context/agentHarnessState';
import {
  SurfaceID,
  SurfaceStatus,
} from '../../../generated/api/context/agentHarnessSurfaces';
import {
  createPersonaAgentHarness,
  getAgentHarnessSurfaces,
  getDefaultAgentHarness,
  getPersonaAgentHarness,
  listAgentHarnessPersonas,
  setAgentHarnessEnabled,
} from '../../../rest/AgentHarnessAPI';
import AgentHarnessListPage from './AgentHarnessListPage';

const mockNavigate = jest.fn();
const mockAuth = { isAdminUser: true };

jest.mock('../../../rest/AgentHarnessAPI', () => ({
  createPersonaAgentHarness: jest.fn(),
  getAgentHarnessSurfaces: jest.fn(),
  getDefaultAgentHarness: jest.fn(),
  getPersonaAgentHarness: jest.fn(),
  listAgentHarnessPersonas: jest.fn(),
  setAgentHarnessEnabled: jest.fn(),
}));

jest.mock('../../../rest/userAPI', () => ({
  getUsers: jest.fn().mockResolvedValue({ data: [], paging: { total: 120 } }),
}));

jest.mock('../../../rest/contextMemoryAPI', () => ({
  getListContextMemories: jest
    .fn()
    .mockResolvedValue({ data: [], paging: { total: 4 } }),
}));

jest.mock('../../../hooks/authHooks', () => ({
  useAuth: () => mockAuth,
}));

jest.mock('react-router-dom', () => ({
  ...jest.requireActual('react-router-dom'),
  useNavigate: () => mockNavigate,
}));

jest.mock(
  '../../../components/ContextCenter/ContextCenterHeader/ContextCenterHeader.component',
  () =>
    ({ actionsSlot }: { actionsSlot?: ReactNode }) =>
      <div data-testid="context-center-header">{actionsSlot}</div>
);

jest.mock(
  '../../../components/common/DocumentTitle/DocumentTitle',
  () => () => null
);

const summary = (
  name: string,
  overrides: Partial<AgentHarnessPersonaSummary> = {}
): AgentHarnessPersonaSummary => ({
  cacheState: CacheState.Fresh,
  characterBudget: 400000,
  charactersUsed: 0,
  enabledEvents: [HookEvent.SessionStart],
  persona: {
    displayName: name,
    fullyQualifiedName: name,
    id: `${name}-id`,
    name,
    type: 'persona',
  },
  status: HarnessStatus.Inherited,
  userCount: 2,
  ...overrides,
});

const DEFAULT_STATE = {
  draftRevision: 0,
  published: {
    context: {
      rules: [{ entityType: 'table', id: 'r1', name: 'Certified tables' }],
    },
    events: [{ enabled: true, id: HookEvent.SessionStart, tokenBudget: 6000 }],
    guardrails: [],
    id: 'default-id',
    kind: 'Default',
    name: 'default',
    perTurnTokenBudget: 20000,
    version: 1.3,
  },
  resolved: {
    cacheTtlMinutes: 30,
    characterBudget: 400000,
    events: [
      {
        enabled: true,
        id: HookEvent.SessionStart,
        inherited: false,
        tokenBudget: 6000,
      },
    ],
    guardrails: [{ id: 'certified-only', level: 'Block' }],
    kind: 'Default',
    memory: { review: 'StewardReview' },
    memoryInherited: false,
    perTurnTokenBudget: 20000,
    rules: [
      {
        inherited: false,
        rule: { entityType: 'table', id: 'r1', name: 'Certified tables' },
      },
    ],
    skills: [],
  },
  status: HarnessStatus.Published,
} as unknown as AgentHarnessState;

const ANALYST = summary('Analyst', {
  enabled: true,
  harnessId: 'analyst-harness',
  status: HarnessStatus.Published,
});

const renderPage = () =>
  render(
    <MemoryRouter>
      <AgentHarnessListPage />
    </MemoryRouter>
  );

describe('AgentHarnessListPage', () => {
  beforeEach(() => {
    mockAuth.isAdminUser = true;
    (getDefaultAgentHarness as jest.Mock).mockResolvedValue(DEFAULT_STATE);
    (getAgentHarnessSurfaces as jest.Mock).mockResolvedValue({
      surfaces: [SurfaceID.ClaudeCode, SurfaceID.Codex].map((id) => ({
        connector: true,
        hooks: id === SurfaceID.ClaudeCode,
        id,
        name: id === SurfaceID.ClaudeCode ? 'Claude Code' : 'Codex',
        skills: true,
        status:
          id === SurfaceID.ClaudeCode
            ? SurfaceStatus.Connected
            : SurfaceStatus.NotConnected,
      })),
    });
    (getPersonaAgentHarness as jest.Mock).mockResolvedValue({
      draftRevision: 0,
      published: {
        context: { rules: [] },
        guardrails: [],
        id: 'analyst-harness',
        skills: [{ enabled: true, name: 'explain-metric' }],
      },
      status: HarnessStatus.Published,
    });
    (listAgentHarnessPersonas as jest.Mock).mockResolvedValue({
      data: [summary('Steward'), ANALYST],
      paging: { total: 2 },
    });
  });

  it('shows the company default and the personas sorted by name', async () => {
    renderPage();

    expect(
      await screen.findByTestId('default-harness-status')
    ).toHaveTextContent('label.published-version');
    expect(screen.getByTestId('default-harness-sources')).toBeInTheDocument();
    expect(screen.getByTestId('persona-harness-count')).toHaveTextContent('2');
    expect(
      screen
        .getAllByTestId(/^persona-harness-row-/)
        .map((row) => row.getAttribute('data-testid'))
    ).toEqual(['persona-harness-row-Analyst', 'persona-harness-row-Steward']);

    await waitFor(() =>
      expect(
        within(screen.getByTestId('persona-harness-row-Analyst')).getByText(
          'message.agent-harness-added-skill'
        )
      ).toBeInTheDocument()
    );

    expect(getPersonaAgentHarness).toHaveBeenCalledTimes(1);
    expect(getPersonaAgentHarness).toHaveBeenCalledWith('Analyst');
  });

  it('counts connected surfaces and names the first one still to connect', async () => {
    renderPage();

    const cell = await screen.findByTestId('default-harness-surfaces');

    await waitFor(() =>
      expect(cell).toHaveTextContent('message.agent-harness-surfaces-connected')
    );

    expect(cell).toHaveTextContent(
      'message.agent-harness-surface-not-connected'
    );
  });

  it('shows no surface count when the status cannot be read', async () => {
    (getAgentHarnessSurfaces as jest.Mock).mockRejectedValue(new Error('403'));
    renderPage();

    const cell = await screen.findByTestId('default-harness-surfaces');

    await waitFor(() => expect(getAgentHarnessSurfaces).toHaveBeenCalled());

    expect(cell).toHaveTextContent('—');
    expect(cell).not.toHaveTextContent(
      'message.agent-harness-surfaces-connected'
    );
  });

  it('opens the persona editor from its row', async () => {
    renderPage();

    fireEvent.click(await screen.findByTestId('persona-harness-row-Analyst'));

    expect(mockNavigate).toHaveBeenCalledWith(
      '/context-center/agent-harness/persona/Analyst'
    );
  });

  it('switches a harness off and back on when the server refuses', async () => {
    (setAgentHarnessEnabled as jest.Mock).mockRejectedValue(new Error('no'));
    renderPage();

    const toggle = within(
      await screen.findByTestId('persona-harness-enabled-Analyst')
    ).getByRole('switch');
    fireEvent.click(toggle);

    expect(setAgentHarnessEnabled).toHaveBeenCalledWith(
      'analyst-harness',
      false
    );

    await waitFor(() => expect(toggle).toBeChecked());

    expect(mockNavigate).not.toHaveBeenCalled();
  });

  it('creates a harness for a persona that has none and opens it', async () => {
    (listAgentHarnessPersonas as jest.Mock).mockResolvedValue({
      data: [summary('Steward'), ANALYST, summary('Consumer')],
      paging: { total: 3 },
    });
    (createPersonaAgentHarness as jest.Mock).mockResolvedValue({});
    renderPage();

    fireEvent.click(await screen.findByTestId('new-persona-harness'));
    const options = await screen.findByTestId('new-persona-harness-options');

    expect(
      within(options)
        .getAllByRole('radio')
        .map((radio) => radio.closest('label')?.textContent)
    ).toEqual([
      expect.stringContaining('Consumer'),
      expect.stringContaining('Steward'),
    ]);

    fireEvent.click(within(options).getByLabelText(/Steward/));
    fireEvent.click(screen.getByTestId('create-persona-harness'));

    await waitFor(() =>
      expect(mockNavigate).toHaveBeenCalledWith(
        '/context-center/agent-harness/persona/Steward'
      )
    );

    expect(createPersonaAgentHarness).toHaveBeenCalledWith('Steward');
    expect(listAgentHarnessPersonas).toHaveBeenLastCalledWith({ limit: 1000 });
  });

  it('shows no harness data to a non-admin', async () => {
    mockAuth.isAdminUser = false;
    renderPage();

    expect(await screen.findByText('label.access-denied')).toBeInTheDocument();
    expect(screen.queryByTestId('new-persona-harness')).not.toBeInTheDocument();
    expect(getDefaultAgentHarness).not.toHaveBeenCalled();
    expect(listAgentHarnessPersonas).not.toHaveBeenCalled();
  });
});
