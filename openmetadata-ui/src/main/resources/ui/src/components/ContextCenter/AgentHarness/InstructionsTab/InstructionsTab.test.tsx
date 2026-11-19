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
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import {
  AgentHarnessSpec,
  HarnessKind,
  MemoryReview,
  MemoryVisibility,
  ResolvedAgentHarness,
} from '../../../../generated/api/context/agentHarnessState';
import { SkillDefinition } from '../../../../generated/api/context/skillDefinition';
import InstructionsTab from './InstructionsTab';

const SKILLS: SkillDefinition[] = [
  {
    defaultEnabled: true,
    description: 'Look up context before answering',
    name: 'find-context',
  },
  {
    defaultEnabled: false,
    description: 'Explain how a metric is defined',
    name: 'metric-definition',
  },
];

const resolvedFor = (kind: HarnessKind): ResolvedAgentHarness => ({
  cacheTtlMinutes: 30,
  characterBudget: 400000,
  companyInstructions: 'Answer from certified tables.',
  events: [],
  guardrails: [],
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
  skills:
    kind === HarnessKind.Persona
      ? [
          { enabled: true, inherited: true, name: 'find-context' },
          { enabled: false, inherited: false, name: 'metric-definition' },
        ]
      : [],
});

const renderTab = (
  kind: HarnessKind,
  props: Partial<React.ComponentProps<typeof InstructionsTab>> = {}
) => {
  const onChange = jest.fn();
  render(
    <InstructionsTab
      canEdit
      showInherited
      kind={kind}
      resolved={resolvedFor(kind)}
      skills={SKILLS}
      spec={{}}
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

const skillSwitch = (name: string) =>
  within(screen.getByTestId(`skill-enabled-${name}`)).getByRole('switch');

describe('InstructionsTab', () => {
  it('edits the company instructions on the default harness', () => {
    const { applied } = renderTab(HarnessKind.Default);

    fireEvent.change(
      within(screen.getByTestId('company-instructions')).getByRole('textbox'),
      { target: { value: 'Cite the glossary term.' } }
    );

    expect(applied().companyInstructions).toBe('Cite the glossary term.');
    expect(screen.queryByTestId('persona-prompt')).not.toBeInTheDocument();
  });

  it('shows the company instructions locked on a persona harness and edits its prompt', () => {
    const { applied } = renderTab(HarnessKind.Persona);

    expect(screen.getByTestId('company-instructions-text')).toHaveTextContent(
      'Answer from certified tables.'
    );
    expect(
      within(screen.getByTestId('company-instructions')).queryByRole('textbox')
    ).not.toBeInTheDocument();

    fireEvent.change(
      within(screen.getByTestId('persona-prompt')).getByRole('textbox'),
      { target: { value: 'You help finance analysts.' } }
    );

    expect(applied({ context: { rules: [] } }).context).toEqual({
      prompt: 'You help finance analysts.',
      rules: [],
    });
  });

  it('locks the skills a persona inherits and hides them with Show inherited off', () => {
    renderTab(HarnessKind.Persona);

    expect(screen.getByTestId('skill-find-context')).toHaveTextContent(
      'label.from-default'
    );
    expect(skillSwitch('find-context')).toBeChecked();
    expect(skillSwitch('find-context')).toHaveAttribute(
      'aria-readonly',
      'true'
    );

    fireEvent.click(skillSwitch('find-context'));

    expect(skillSwitch('find-context')).toBeChecked();
  });

  it('disables Add skill when every registered skill is already listed', () => {
    renderTab(HarnessKind.Default, { skills: [SKILLS[0]] });

    expect(screen.getByTestId('add-skill')).toBeDisabled();
  });

  it('hides inherited skills and instructions with Show inherited off', () => {
    renderTab(HarnessKind.Persona, { showInherited: false });

    expect(screen.queryByTestId('skill-find-context')).not.toBeInTheDocument();
    expect(
      screen.queryByTestId('company-instructions')
    ).not.toBeInTheDocument();
    expect(screen.getByTestId('persona-prompt')).toBeInTheDocument();
  });

  it('turns off a skill the default offers', () => {
    const { applied } = renderTab(HarnessKind.Default);

    expect(skillSwitch('find-context')).toBeChecked();

    fireEvent.click(skillSwitch('find-context'));

    expect(applied().skills).toEqual([
      { enabled: false, name: 'find-context' },
    ]);
  });

  it('adds a registered skill from the picker', async () => {
    const { applied } = renderTab(HarnessKind.Default);

    expect(
      screen.queryByTestId('skill-metric-definition')
    ).not.toBeInTheDocument();

    await act(async () => {
      fireEvent.click(screen.getByTestId('add-skill'));
    });
    fireEvent.click(await screen.findByTestId('add-skill-metric-definition'));

    expect(applied().skills).toEqual([
      { enabled: true, name: 'metric-definition' },
    ]);
  });

  it('is read-only without edit rights', () => {
    renderTab(HarnessKind.Default, { canEdit: false });

    expect(
      within(screen.getByTestId('company-instructions')).getByRole('textbox')
    ).toBeDisabled();
    expect(skillSwitch('find-context')).toBeDisabled();
    expect(screen.queryByTestId('add-skill')).not.toBeInTheDocument();
  });
});
