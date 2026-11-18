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
  AgentHarnessSpec,
  GuardrailLevel,
  HarnessKind,
  MemoryReview,
  MemoryVisibility,
  ResolvedAgentHarness,
  ToolAccess,
  ToolAccessDefault,
  ToolAccessLevel,
} from '../../../../generated/api/context/agentHarnessState';
import { GuardrailDefinition } from '../../../../generated/api/context/guardrailDefinition';
import GuardrailsTab from './GuardrailsTab';

const GUARDRAILS = [
  {
    available: true,
    defaultLevel: 'Warn',
    description: 'Warns when a test case is failing',
    event: 'PreToolUse',
    id: 'flag-incidents',
    name: 'Flag open incidents',
  },
  {
    available: false,
    defaultLevel: 'Off',
    description: 'Stops expensive queries',
    event: 'PreToolUse',
    id: 'query-cost',
    name: 'Query cost ceiling',
  },
] as GuardrailDefinition[];

const INHERITED_ACCESS: ToolAccess = {
  default: ToolAccessDefault.Deny,
  servers: [{ level: ToolAccessLevel.Warn, match: 'github' }],
};

const resolvedFor = (kind: HarnessKind): ResolvedAgentHarness => ({
  cacheTtlMinutes: 30,
  characterBudget: 400000,
  events: [],
  guardrails:
    kind === HarnessKind.Persona
      ? [
          {
            id: 'flag-incidents',
            level: GuardrailLevel.Warn,
            minLevel: GuardrailLevel.Warn,
          },
          {
            id: 'query-cost',
            level: GuardrailLevel.Off,
            minLevel: GuardrailLevel.Off,
          },
        ]
      : [],
  inheritedToolAccess:
    kind === HarnessKind.Persona ? INHERITED_ACCESS : undefined,
  kind,
  memory: {
    capture: false,
    dedup: true,
    linkAssets: true,
    review: MemoryReview.StewardReview,
    visibility: MemoryVisibility.Private,
  },
  memoryInherited: false,
  perTurnTokenBudget: 20000,
  rules: [],
  skills: [],
});

const renderTab = (
  kind: HarnessKind,
  props: Partial<React.ComponentProps<typeof GuardrailsTab>> = {}
) => {
  const onChange = jest.fn();
  render(
    <GuardrailsTab
      canEdit
      showInherited
      guardrails={GUARDRAILS}
      kind={kind}
      resolved={resolvedFor(kind)}
      spec={{}}
      toolAccessErrors={{ servers: {} }}
      onChange={onChange}
      {...props}
    />
  );
  const applied = (spec: AgentHarnessSpec = {}) => {
    const [change] = onChange.mock.lastCall;

    return change(spec) as AgentHarnessSpec;
  };

  return { applied, onChange };
};

const level = (group: string, option: string) =>
  within(screen.getByTestId(group)).getByTestId(`${group}-${option}`);

describe('GuardrailsTab', () => {
  it('disables the levels below the default minimum on a persona harness', () => {
    renderTab(HarnessKind.Persona);

    const group = within(screen.getByTestId('guardrail-flag-incidents'));

    expect(group.getByRole('radiogroup')).toBeInTheDocument();
    expect(group.getByText('label.default-minimum-level')).toBeInTheDocument();
    expect(level('guardrail-level-flag-incidents', 'Off')).toBeDisabled();
    expect(level('guardrail-level-flag-incidents', 'Warn')).toBeEnabled();
    expect(level('guardrail-level-flag-incidents', 'Block')).toBeEnabled();
  });

  it('raises a guardrail to Block', () => {
    const { applied } = renderTab(HarnessKind.Persona);

    fireEvent.click(level('guardrail-level-flag-incidents', 'Block'));

    expect(applied().guardrails).toEqual([
      { id: 'flag-incidents', level: GuardrailLevel.Block },
    ]);
  });

  it('keeps a guardrail the server cannot evaluate yet switched off', () => {
    renderTab(HarnessKind.Default);

    const row = within(screen.getByTestId('guardrail-query-cost'));

    expect(
      row.getByText('message.agent-harness-needs-answer-harness')
    ).toBeInTheDocument();
    expect(level('guardrail-level-query-cost', 'Warn')).toBeDisabled();
    expect(level('guardrail-level-query-cost', 'Off')).toBeDisabled();
  });

  it('offers every level on the default harness', () => {
    renderTab(HarnessKind.Default);

    expect(level('guardrail-level-flag-incidents', 'Off')).toBeEnabled();
    expect(
      screen.queryByText('label.default-minimum-level')
    ).not.toBeInTheDocument();
  });

  it('shows the default MCP entries locked and keeps a persona from allowing unlisted servers', () => {
    renderTab(HarnessKind.Persona);

    expect(screen.getByTestId('mcp-inherited-github')).toHaveTextContent(
      'label.inherited-locked'
    );
    expect(level('mcp-default', 'Allow')).toBeDisabled();
    expect(level('mcp-default', 'Deny')).toHaveAttribute(
      'aria-checked',
      'true'
    );
  });

  it('hides the inherited MCP entries when Show inherited is off', () => {
    renderTab(HarnessKind.Persona, { showInherited: false });

    expect(
      screen.queryByTestId('mcp-inherited-github')
    ).not.toBeInTheDocument();
  });

  it('adds a server entry that blocks, starting from the inherited default', () => {
    const { applied } = renderTab(HarnessKind.Persona);

    fireEvent.click(screen.getByTestId('add-mcp-server'));

    expect(applied().toolAccess).toEqual({
      default: ToolAccessDefault.Deny,
      servers: [{ level: ToolAccessLevel.Block, match: '' }],
    });
  });

  it('floors a persona entry at the level the default sets for that server', () => {
    renderTab(HarnessKind.Persona, {
      spec: {
        toolAccess: {
          default: ToolAccessDefault.Deny,
          servers: [{ level: ToolAccessLevel.Block, match: 'github' }],
        },
      },
    });

    expect(level('mcp-server-level-0', 'Allow')).toBeDisabled();
    expect(level('mcp-server-level-0', 'Warn')).toBeEnabled();
  });

  it('explains an entry the server would refuse', () => {
    renderTab(HarnessKind.Default, {
      spec: {
        toolAccess: {
          default: ToolAccessDefault.Allow,
          servers: [{ level: ToolAccessLevel.Block, match: '' }],
        },
      },
      toolAccessErrors: { servers: { 0: 'matchRequired' } },
    });

    expect(
      screen.getByText('message.agent-harness-mcp-server-required')
    ).toBeInTheDocument();
  });

  it('removes a server entry', () => {
    const spec: AgentHarnessSpec = {
      toolAccess: {
        default: ToolAccessDefault.Deny,
        servers: [{ level: ToolAccessLevel.Block, match: 'slack' }],
      },
    };
    const { applied } = renderTab(HarnessKind.Default, { spec });

    fireEvent.click(screen.getByTestId('mcp-server-remove-0'));

    expect(applied(spec).toolAccess).toEqual({
      default: ToolAccessDefault.Deny,
      servers: [],
    });
  });

  it('is read-only without edit rights', () => {
    renderTab(HarnessKind.Default, { canEdit: false });

    expect(level('guardrail-level-flag-incidents', 'Block')).toBeDisabled();
    expect(level('mcp-default', 'Deny')).toBeDisabled();
    expect(screen.queryByTestId('add-mcp-server')).not.toBeInTheDocument();
  });
});
