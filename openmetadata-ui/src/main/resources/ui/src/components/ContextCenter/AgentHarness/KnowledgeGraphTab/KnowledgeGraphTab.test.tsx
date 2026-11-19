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

const CONNECTIONS = [
  {
    description: 'The company code and issues.',
    displayName: 'GitHub',
    name: 'github',
    tools: [{ name: 'search_issues' }, { name: 'create_issue' }],
  },
  {
    displayName: 'Notion',
    name: 'notion',
    tools: [{ name: 'search' }],
  },
];

const renderTab = (
  props: Partial<React.ComponentProps<typeof KnowledgeGraphTab>> = {}
) => {
  const onChange = jest.fn();
  render(
    <KnowledgeGraphTab
      canEdit
      connections={CONNECTIONS}
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
      connections: [],
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

  it('lists each registered connection off by default with its tool count', () => {
    renderTab({ kind: HarnessKind.Default });

    const github = screen.getByTestId('knowledge-graph-connection-row-github');

    expect(github).toHaveTextContent('GitHub');
    expect(github).toHaveTextContent('The company code and issues.');
    expect(github).toHaveTextContent(
      'message.agent-harness-kg-connection-tool-count'
    );
    expect(toggle('knowledge-graph-connection-row-github')).not.toBeChecked();
    expect(toggle('knowledge-graph-connection-row-notion')).not.toBeChecked();
  });

  it('switches a connection on', () => {
    const { applied } = renderTab({ kind: HarnessKind.Default });

    fireEvent.click(toggle('knowledge-graph-connection-row-github'));

    expect(applied().knowledgeGraph?.connections).toEqual([
      { enabled: true, name: 'github' },
    ]);
  });

  it('locks a connection the default enables on a persona harness', () => {
    const { applied } = renderTab({
      inheritedKnowledgeGraph: {
        connections: [{ enabled: true, name: 'github' }],
      },
    });

    expect(toggle('knowledge-graph-connection-row-github')).toBeChecked();
    expect(toggle('knowledge-graph-connection-row-github')).toBeDisabled();
    expect(
      screen.getByTestId('knowledge-graph-connection-row-github')
    ).toHaveTextContent('label.from-default');

    fireEvent.click(toggle('knowledge-graph-connection-row-notion'));

    expect(applied().knowledgeGraph?.connections).toEqual([
      { enabled: true, name: 'notion' },
    ]);
  });

  it('says when no connection is registered', () => {
    renderTab({ connections: [] });

    expect(
      screen.getByTestId('knowledge-graph-no-connections')
    ).toBeInTheDocument();
  });

  it('shows an enabled connection no longer registered so it can be switched off', () => {
    const { applied } = renderTab({
      kind: HarnessKind.Default,
      spec: {
        knowledgeGraph: {
          connections: [
            { enabled: true, name: 'github' },
            { enabled: true, name: 'jira' },
          ],
        },
      },
    });

    expect(
      screen.getByTestId('knowledge-graph-connection-gone-jira')
    ).toBeInTheDocument();
    expect(toggle('knowledge-graph-connection-row-jira')).toBeChecked();

    fireEvent.click(toggle('knowledge-graph-connection-row-jira'));

    expect(applied().knowledgeGraph?.connections).toEqual([
      { enabled: true, name: 'github' },
    ]);
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
