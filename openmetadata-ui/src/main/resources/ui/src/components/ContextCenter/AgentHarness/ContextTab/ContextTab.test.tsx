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
import {
  AgentHarnessSpec,
  ContextRule,
  HarnessKind,
  MemoryReview,
  MemoryVisibility,
  ResolvedAgentHarness,
} from '../../../../generated/api/context/agentHarnessState';
import { searchHarnessSource } from '../../../../rest/AgentHarnessAPI';
import { pickedIds } from '../../../../utils/discovery/context-center/agentHarness.utils';
import ContextTab from './ContextTab';

jest.mock('../../../../rest/AgentHarnessAPI', () => ({
  searchHarnessSource: jest.fn(),
}));

// The rule editor's query builder needs the search config; this stand-in submits one rule.
jest.mock('../ContextRuleEditor/ContextRuleEditor.component', () => ({
  ContextRuleEditor: ({
    open,
    onSubmit,
  }: {
    open: boolean;
    onSubmit: (rule: ContextRule) => Promise<void>;
  }) =>
    open ? (
      <button
        data-testid="submit-rule"
        onClick={() =>
          onSubmit({ entityType: 'dashboard', name: 'Sales dashboards' })
        }>
        submit
      </button>
    ) : null,
}));

const METRICS = [
  { fullyQualifiedName: 'revenue', id: 'm1', name: 'revenue' },
  { fullyQualifiedName: 'churn', id: 'm2', name: 'churn' },
];

const pickedRule = (entityType: string, ids: string[]): ContextRule => ({
  entityType,
  id: `picked-${entityType}`,
  name: `Picked ${entityType}`,
  picked: true,
  queryFilter: JSON.stringify({
    query: { bool: { must: [{ terms: { id: ids } }] } },
  }),
});

const DEFAULT_TABLES: ContextRule = {
  entityType: 'table',
  id: 'default-tables',
  name: 'Certified tables',
};
const OWN_TABLES: ContextRule = {
  entityType: 'table',
  id: 'own-tables',
  name: 'Finance tables',
};

const SPEC: AgentHarnessSpec = {
  context: {
    cacheTtlMinutes: 30,
    characterBudget: 400000,
    rules: [OWN_TABLES, pickedRule('metric', ['m1'])],
  },
  perTurnTokenBudget: 20000,
};

const RESOLVED: ResolvedAgentHarness = {
  cacheTtlMinutes: 30,
  characterBudget: 400000,
  events: [],
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
  rules: [
    { inherited: true, rule: DEFAULT_TABLES },
    { inherited: true, rule: pickedRule('glossaryTerm', ['t1', 't2']) },
    { inherited: false, rule: OWN_TABLES },
  ],
  skills: [],
};

const renderTab = (showInherited = true) => {
  const onChange = jest.fn();
  render(
    <ContextTab
      canEdit
      harnessId="harness-1"
      resolved={RESOLVED}
      showInherited={showInherited}
      spec={SPEC}
      onChange={onChange}
    />
  );
  const applied = () => {
    const [change] = onChange.mock.lastCall;

    return change(SPEC) as AgentHarnessSpec;
  };

  return { applied };
};

describe('ContextTab', () => {
  beforeEach(() => {
    (searchHarnessSource as jest.Mock).mockImplementation(
      async (_source, { ids }: { ids?: string[] }) =>
        ids ? METRICS.filter(({ id }) => ids.includes(id)) : METRICS
    );
  });

  it('counts what this harness adds and what it inherits per source', async () => {
    renderTab();

    expect(screen.getByTestId('source-count-metrics')).toHaveTextContent('1');
    expect(screen.getByTestId('source-count-assets')).toHaveTextContent('1');
    expect(screen.getByTestId('source-count-glossaryTerms')).toHaveTextContent(
      '0'
    );
    expect(
      screen.getByTestId('source-inherited-glossaryTerms')
    ).toHaveTextContent('message.agent-harness-inherited-count');
    expect(
      await within(screen.getByTestId('source-card-metrics')).findByText(
        'revenue'
      )
    ).toBeInTheDocument();
  });

  it('shows the default rules locked beside the rules of this harness', () => {
    renderTab();

    expect(
      within(screen.getByTestId('inherited-context-rule-card')).getByText(
        'Certified tables'
      )
    ).toBeInTheDocument();
    expect(screen.getByTestId('asset-rule-count')).toHaveTextContent('2');
  });

  it('leaves inherited rules out when Show inherited is off', () => {
    renderTab(false);

    expect(
      screen.queryByTestId('inherited-context-rule-card')
    ).not.toBeInTheDocument();
    expect(screen.getByTestId('asset-rule-count')).toHaveTextContent('1');
  });

  it('adds a rule with an id the draft can address', () => {
    const { applied } = renderTab();

    fireEvent.click(screen.getByTestId('add-asset-rule'));
    fireEvent.click(screen.getByTestId('submit-rule'));

    const added = applied().context?.rules?.[2];

    expect(added).toEqual({
      entityType: 'dashboard',
      id: expect.any(String),
      name: 'Sales dashboards',
    });
  });

  it('writes picked metrics as the always-in-context rule', async () => {
    const { applied } = renderTab();

    fireEvent.click(screen.getByTestId('manage-metrics'));
    fireEvent.click(
      within(await screen.findByTestId('source-picker-item-churn')).getByRole(
        'checkbox'
      )
    );
    fireEvent.click(screen.getByTestId('source-picker-save'));

    await waitFor(() =>
      expect(pickedIds(applied().context?.rules?.[1] as ContextRule)).toEqual([
        'm1',
        'm2',
      ])
    );

    expect(applied().context?.rules?.[1]).toEqual(
      expect.objectContaining({
        alwaysInContext: true,
        entityType: 'metric',
        id: 'picked-metric',
        picked: true,
      })
    );
  });
});
