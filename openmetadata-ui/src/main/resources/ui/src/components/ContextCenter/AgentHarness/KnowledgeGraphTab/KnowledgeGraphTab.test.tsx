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
} from '../../../../generated/api/context/agentHarnessState';
import KnowledgeGraphTab from './KnowledgeGraphTab';

const SUB_AGENTS = [
  {
    description: 'Answers questions with SQL against the warehouse.',
    displayName: 'Data analyst',
    name: 'dataAnalyst',
  },
];

const renderTab = (
  props: Partial<React.ComponentProps<typeof KnowledgeGraphTab>> = {}
) => {
  const onChange = jest.fn();
  render(
    <KnowledgeGraphTab
      canEdit
      kind={HarnessKind.Persona}
      spec={{}}
      subAgents={SUB_AGENTS}
      onChange={onChange}
      {...props}
    />
  );
  const applied = (spec: AgentHarnessSpec = {}) => {
    const [change] = onChange.mock.lastCall;

    return change(spec) as AgentHarnessSpec;
  };

  return { applied };
};

const toggle = (testId: string) =>
  within(screen.getByTestId(testId)).getByRole('switch');

describe('KnowledgeGraphTab', () => {
  it('shows the defaults on a persona harness that sets none', () => {
    renderTab();

    expect(
      screen.getByTestId('knowledge-graph-from-default')
    ).toBeInTheDocument();
    expect(toggle('knowledge-graph-entry')).not.toBeChecked();
    expect(toggle('knowledge-graph-rawQueries')).toBeChecked();
    expect(toggle('knowledge-graph-learn')).toBeChecked();
    expect(toggle('knowledge-graph-sub-agent-dataAnalyst')).not.toBeChecked();
  });

  it('writes the whole block when a switch changes', () => {
    const { applied } = renderTab();

    fireEvent.click(toggle('knowledge-graph-rawQueries'));

    expect(applied().knowledgeGraph).toEqual({
      entry: false,
      learn: true,
      rawQueries: false,
      subAgents: [],
    });
  });

  it('enables a registered sub-agent', () => {
    const { applied } = renderTab();

    fireEvent.click(toggle('knowledge-graph-sub-agent-dataAnalyst'));

    expect(applied().knowledgeGraph?.subAgents).toEqual([
      { enabled: true, name: 'dataAnalyst' },
    ]);
  });

  it('says when no sub-agent is registered', () => {
    renderTab({ subAgents: [] });

    expect(
      screen.getByTestId('knowledge-graph-no-sub-agents')
    ).toBeInTheDocument();
  });

  it('returns a persona harness to the default settings', () => {
    const { applied } = renderTab({
      spec: { knowledgeGraph: { entry: true } },
    });

    expect(toggle('knowledge-graph-entry')).toBeChecked();

    fireEvent.click(screen.getByTestId('knowledge-graph-use-default'));

    expect(
      applied({ knowledgeGraph: { entry: true } }).knowledgeGraph
    ).toBeUndefined();
  });
});
