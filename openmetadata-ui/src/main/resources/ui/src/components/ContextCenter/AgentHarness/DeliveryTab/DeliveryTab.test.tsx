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
  HarnessKind,
  HookEvent,
} from '../../../../generated/api/context/agentHarnessState';
import { DeliveryRow } from '../../../../interface/discovery/agentHarness.interface';
import DeliveryTab from './DeliveryTab';

const ROWS: DeliveryRow[] = [
  {
    enabled: true,
    id: HookEvent.SessionStart,
    inherited: true,
    maxTokenBudget: 6000,
    tokenBudget: 6000,
  },
  {
    enabled: true,
    id: HookEvent.UserPromptSubmit,
    inherited: true,
    maxTokenBudget: 4000,
    tokenBudget: 4000,
  },
  {
    enabled: true,
    id: HookEvent.PreToolUse,
    inherited: true,
    maxTokenBudget: 2000,
    tokenBudget: 2000,
  },
  {
    enabled: false,
    id: HookEvent.PostToolUse,
    inherited: false,
    maxTokenBudget: 1000,
    tokenBudget: 1000,
  },
  {
    enabled: false,
    id: HookEvent.Stop,
    inherited: false,
    maxTokenBudget: 1000,
    tokenBudget: 1000,
  },
];

const SPEC: AgentHarnessSpec = { perTurnTokenBudget: 20000 };

const renderTab = (
  props: Partial<React.ComponentProps<typeof DeliveryTab>> = {}
) => {
  const onChange = jest.fn();
  render(
    <DeliveryTab
      canEdit
      errors={{ events: {} }}
      kind={HarnessKind.Persona}
      perTurnTokenBudget={20000}
      rows={ROWS}
      onChange={onChange}
      {...props}
    />
  );
  const applied = () => {
    const [change] = onChange.mock.lastCall;

    return change(SPEC) as AgentHarnessSpec;
  };

  return { applied, onChange };
};

const switchOf = (event: HookEvent) =>
  within(screen.getByTestId(`delivery-enabled-${event}`)).getByRole('switch');

describe('DeliveryTab', () => {
  it('lists the five events in delivery order and marks the inherited ones', () => {
    renderTab();

    expect(
      screen
        .getAllByTestId(/^delivery-event-/)
        .map((row) => row.getAttribute('data-testid'))
    ).toEqual([
      'delivery-event-SessionStart',
      'delivery-event-UserPromptSubmit',
      'delivery-event-PreToolUse',
      'delivery-event-PostToolUse',
      'delivery-event-Stop',
    ]);
    expect(screen.getAllByText('label.from-default')).toHaveLength(3);
    expect(switchOf(HookEvent.SessionStart)).toBeDisabled();
    expect(switchOf(HookEvent.Stop)).not.toBeDisabled();
    expect(screen.getByTestId('delivery-event-Stop')).toHaveClass(
      'tw:opacity-55'
    );
  });

  it('turns on an event the persona adds, keeping its budget', () => {
    const { applied } = renderTab();

    fireEvent.click(switchOf(HookEvent.Stop));

    expect(applied().events).toEqual([
      { enabled: true, id: HookEvent.Stop, tokenBudget: 1000 },
    ]);
  });

  it('lowers the budget of an inherited event', () => {
    const { applied } = renderTab();

    const input = screen.getByTestId('delivery-budget-SessionStart');
    fireEvent.change(input, { target: { value: '3000' } });
    fireEvent.blur(input);

    expect(applied().events).toEqual([
      { enabled: true, id: HookEvent.SessionStart, tokenBudget: 3000 },
    ]);
  });

  it('explains an event budget above the default and a short per-turn budget', () => {
    renderTab({
      errors: {
        events: { [HookEvent.SessionStart]: 'aboveDefault' },
        perTurnTokenBudget: 'belowEvents',
      },
    });

    expect(
      screen.getByText('message.agent-harness-budget-above-default')
    ).toBeInTheDocument();
    expect(screen.getByTestId('delivery-per-turn-summary')).toHaveClass(
      'tw:text-error-primary'
    );
  });

  it('is read-only without edit rights', () => {
    renderTab({ canEdit: false });

    expect(switchOf(HookEvent.Stop)).toBeDisabled();
    expect(screen.getByTestId('delivery-budget-SessionStart')).toBeDisabled();
  });
});
