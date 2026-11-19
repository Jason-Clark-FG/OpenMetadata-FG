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
import userEvent from '@testing-library/user-event';
import { useEffect, useState } from 'react';
import {
  AgentHarnessSpec,
  ToolAccess,
  ToolAccessDefault,
  ToolAccessLevel,
} from '../../../../generated/api/context/agentHarnessState';
import { McpConnection } from '../../../../interface/discovery/agentHarness.interface';
import McpAccessCard, { McpAccessCardProps } from './McpAccessCard';

jest.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, params?: Record<string, string>) =>
      params ? `${key} ${JSON.stringify(params)}` : key,
  }),
}));

const CONNECTIONS: McpConnection[] = [
  {
    description: 'Code hosting',
    displayName: 'GitHub',
    name: 'github',
    tools: [
      { description: 'Find issues by text', name: 'search_issues' },
      { description: 'Open a new issue', name: 'create_issue' },
    ],
  },
  { displayName: 'Notion', name: 'notion', tools: [] },
];

const CardWithState = ({
  initial,
  onSpec,
  ...props
}: Partial<McpAccessCardProps> & {
  initial: ToolAccess;
  onSpec: (spec: AgentHarnessSpec) => void;
}) => {
  const [spec, setSpec] = useState<AgentHarnessSpec>({ toolAccess: initial });

  useEffect(() => onSpec(spec), [onSpec, spec]);

  return (
    <McpAccessCard
      canEdit
      showInherited
      access={spec.toolAccess}
      errors={{ servers: {} }}
      isPersona={false}
      onChange={(change) => setSpec(change)}
      {...props}
    />
  );
};

const renderCard = (
  servers: ToolAccess['servers'],
  props: Partial<McpAccessCardProps> & { initial?: ToolAccess } = {}
) => {
  const onSpec = jest.fn();
  render(
    <CardWithState
      connections={CONNECTIONS}
      initial={{ default: ToolAccessDefault.Allow, servers }}
      onSpec={onSpec}
      {...props}
    />
  );
  const latestServers = () =>
    (onSpec.mock.lastCall[0] as AgentHarnessSpec).toolAccess?.servers ?? [];

  return { latestServers };
};

// Jest runs on fake timers here, so user-event must advance them.
const user = userEvent.setup({ advanceTimers: jest.advanceTimersByTime });

describe('McpAccessCard', () => {
  it('keeps the free-text fields and adds nothing when no connection is registered', () => {
    const { latestServers } = renderCard(
      [{ level: ToolAccessLevel.Block, match: 'slack' }],
      { connections: [] }
    );

    const match = screen.getByTestId('mcp-server-match-0');

    expect(match.tagName).toBe('INPUT');
    expect(screen.getByTestId('mcp-server-tools-0').tagName).toBe('INPUT');
    expect(screen.queryByTestId('mcp-access-coverage')).not.toBeInTheDocument();
    expect(
      screen.queryByTestId('mcp-uncovered-connections')
    ).not.toBeInTheDocument();
    expect(
      screen.queryByTestId('mcp-server-registered-0')
    ).not.toBeInTheDocument();

    fireEvent.change(match, { target: { value: 'linear' } });

    expect(latestServers()[0].match).toBe('linear');
  });

  it('says what the rules cover when connections are registered', () => {
    renderCard([]);

    expect(screen.getByTestId('mcp-access-coverage')).toHaveTextContent(
      'message.agent-harness-mcp-access-coverage'
    );
  });

  it('fills match with the name of the picked connection', async () => {
    const { latestServers } = renderCard([
      { level: ToolAccessLevel.Block, match: '' },
    ]);

    await user.click(
      within(screen.getByTestId('mcp-server-match-0')).getByRole('combobox')
    );
    await user.click(await screen.findByTestId('mcp-connection-option-github'));

    expect(latestServers()[0].match).toBe('github');
    expect(screen.getByTestId('mcp-server-registered-0')).toBeInTheDocument();
  });

  it('still accepts a host server name typed as free text', async () => {
    const { latestServers } = renderCard([
      { level: ToolAccessLevel.Block, match: '' },
    ]);

    const input = within(screen.getByTestId('mcp-server-match-0')).getByRole(
      'combobox'
    );
    await user.type(input, 'jira');
    await user.tab();

    expect(latestServers()[0].match).toBe('jira');
    expect(
      screen.queryByTestId('mcp-server-registered-0')
    ).not.toBeInTheDocument();
    expect(screen.getByTestId('mcp-server-tools-0').tagName).toBe('INPUT');
  });

  it('writes the picked tools of a registered connection, matched without regard to case', async () => {
    const { latestServers } = renderCard([
      { level: ToolAccessLevel.Allow, match: 'GitHub' },
    ]);

    const picker = screen.getByTestId('mcp-server-tool-picker-0');
    await user.click(within(picker).getByRole('combobox'));

    const option = await screen.findByTestId('mcp-tool-option-search_issues');

    expect(option).toHaveTextContent('Find issues by text');

    await user.click(option);

    expect(latestServers()[0].tools).toEqual(['search_issues']);

    await user.click(within(picker).getByRole('combobox'));
    await user.click(await screen.findByTestId('mcp-tool-option-create_issue'));

    expect(latestServers()[0].tools).toEqual(['search_issues', 'create_issue']);
  });

  it('clears the tools back to every tool when the last one is removed', async () => {
    const { latestServers } = renderCard([
      {
        level: ToolAccessLevel.Allow,
        match: 'github',
        tools: ['search_issues'],
      },
    ]);

    await user.click(
      within(screen.getByTestId('mcp-server-tool-picker-0')).getByRole(
        'button',
        { name: /remove/i }
      )
    );

    expect(latestServers()[0].tools).toBeUndefined();
  });

  it('marks only the rows that name a registered connection', () => {
    renderCard([
      { level: ToolAccessLevel.Allow, match: 'github' },
      { level: ToolAccessLevel.Block, match: 'slack' },
    ]);

    expect(screen.getByTestId('mcp-server-registered-0')).toHaveTextContent(
      'label.registered-connection'
    );
    expect(
      screen.queryByTestId('mcp-server-registered-1')
    ).not.toBeInTheDocument();
    expect(screen.getByTestId('mcp-server-tools-1').tagName).toBe('INPUT');
  });

  it('lists the registered connections with no rule and the default they follow', () => {
    renderCard([], {
      initial: {
        default: ToolAccessDefault.Deny,
        servers: [{ level: ToolAccessLevel.Allow, match: 'github' }],
      },
    });

    expect(screen.getByTestId('mcp-uncovered-connections')).toHaveTextContent(
      '"connections":"Notion","default":"label.deny"'
    );
  });

  it('lists every registered connection while no rule exists', () => {
    renderCard([]);

    expect(screen.getByTestId('mcp-uncovered-connections')).toHaveTextContent(
      '"connections":"GitHub, Notion","default":"label.allow"'
    );
  });

  it('counts a rule inherited from the default as covering its connection', () => {
    renderCard([], {
      inherited: {
        default: ToolAccessDefault.Allow,
        servers: [{ level: ToolAccessLevel.Warn, match: 'notion' }],
      },
      isPersona: true,
    });

    expect(screen.getByTestId('mcp-uncovered-connections')).toHaveTextContent(
      '"connections":"GitHub"'
    );
  });

  it('hides the line once every connection has a rule', () => {
    renderCard([
      { level: ToolAccessLevel.Allow, match: 'github' },
      { level: ToolAccessLevel.Block, match: 'notion' },
    ]);

    expect(
      screen.queryByTestId('mcp-uncovered-connections')
    ).not.toBeInTheDocument();
  });

  it('is read-only without edit rights', () => {
    renderCard([{ level: ToolAccessLevel.Allow, match: 'github' }], {
      canEdit: false,
    });

    expect(
      within(screen.getByTestId('mcp-server-match-0')).getByRole('combobox')
    ).toBeDisabled();
    expect(
      within(screen.getByTestId('mcp-server-tool-picker-0')).getByRole(
        'combobox'
      )
    ).toBeDisabled();
  });
});
