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
import { fireEvent, render, screen, within } from '@testing-library/react';
import {
  AgentHarnessPersonaSummary,
  CacheState,
  HarnessStatus,
  HookEvent,
} from '../../../../generated/api/context/agentHarnessPersonaSummary';
import PersonaHarnessTable from './PersonaHarnessTable';

const row = (
  name: string,
  overrides: Partial<AgentHarnessPersonaSummary>
): AgentHarnessPersonaSummary => ({
  cacheState: CacheState.Fresh,
  characterBudget: 400000,
  charactersUsed: 0,
  enabledEvents: [HookEvent.SessionStart],
  persona: { id: `${name}-id`, name, type: 'persona', displayName: name },
  status: HarnessStatus.Inherited,
  userCount: 3,
  ...overrides,
});

const ROWS = [
  row('Analyst', {
    additions: {
      articles: 0,
      glossaryTerms: 18,
      guardrails: 0,
      metrics: 0,
      rules: 1,
      skills: 0,
    },
    charactersUsed: 248000,
    enabled: true,
    enabledEvents: [
      HookEvent.SessionStart,
      HookEvent.UserPromptSubmit,
      HookEvent.PreToolUse,
    ],
    harnessId: 'h-analyst',
    lastGeneratedAt: 1,
    status: HarnessStatus.Draft,
  }),
  row('Consumer', {}),
  row('Steward', {
    enabled: false,
    harnessId: 'h-steward',
    status: HarnessStatus.NotConfigured,
  }),
  row('Engineer', {
    enabled: true,
    harnessId: 'h-engineer',
    status: HarnessStatus.Published,
  }),
];

const renderTable = (canEdit = true) => {
  const onOpen = jest.fn();
  const onEnabledChange = jest.fn();
  render(
    <PersonaHarnessTable
      canEdit={canEdit}
      rows={ROWS}
      onEnabledChange={onEnabledChange}
      onOpen={onOpen}
    />
  );

  return { onEnabledChange, onOpen };
};

describe('PersonaHarnessTable', () => {
  it('shows the seven columns', () => {
    renderTable();

    expect(
      screen.getAllByRole('columnheader').map((header) => header.textContent)
    ).toEqual([
      'label.persona',
      'label.user-plural',
      'label.harness',
      'label.delivered-at',
      'label.context-budget-used',
      'label.status',
      'label.enabled',
    ]);
  });

  it('labels each status with its badge', () => {
    renderTable();

    expect(
      screen
        .getAllByTestId('persona-harness-status')
        .map((badge) => badge.textContent)
    ).toEqual([
      'label.draft-changes',
      'label.inherited',
      'label.not-configured',
      'label.published',
    ]);
  });

  it('summarises a persona harness and its delivery and budget', () => {
    renderTable();
    const analyst = screen.getByTestId('persona-harness-row-Analyst');

    expect(
      within(analyst).getByText('label.extends-default')
    ).toBeInTheDocument();
    expect(
      within(analyst).getByText(
        'message.agent-harness-added-term · message.agent-harness-added-rule'
      )
    ).toBeInTheDocument();
    expect(within(analyst).getByText('label.session')).toBeInTheDocument();
    expect(within(analyst).getByText('label.prompt')).toBeInTheDocument();
    expect(within(analyst).getByText('label.tool-use')).toBeInTheDocument();
    expect(
      within(analyst).getByText('message.agent-harness-budget-percent')
    ).toBeInTheDocument();

    const consumer = screen.getByTestId('persona-harness-row-Consumer');

    expect(
      within(consumer).getByText('label.uses-default')
    ).toBeInTheDocument();
    expect(
      within(consumer).getByText('label.not-measured-yet')
    ).toBeInTheDocument();
  });

  it('opens the persona when its row is clicked', () => {
    const { onOpen } = renderTable();

    fireEvent.click(screen.getByTestId('persona-harness-row-Engineer'));

    expect(onOpen).toHaveBeenCalledWith(ROWS[3]);
  });

  it('switches a harness without opening the row', () => {
    const { onEnabledChange, onOpen } = renderTable();

    fireEvent.click(
      within(screen.getByTestId('persona-harness-enabled-Steward')).getByRole(
        'switch'
      )
    );

    expect(onEnabledChange).toHaveBeenCalledWith(ROWS[2], true);
    expect(onOpen).not.toHaveBeenCalled();
  });

  it('disables the switches for non-admins and for personas without a harness', () => {
    renderTable(false);

    expect(
      within(screen.getByTestId('persona-harness-enabled-Analyst')).getByRole(
        'switch'
      )
    ).toBeDisabled();

    renderTable(true);

    expect(
      within(
        screen.getAllByTestId('persona-harness-enabled-Consumer')[1]
      ).getByRole('switch')
    ).toBeDisabled();
  });
});
