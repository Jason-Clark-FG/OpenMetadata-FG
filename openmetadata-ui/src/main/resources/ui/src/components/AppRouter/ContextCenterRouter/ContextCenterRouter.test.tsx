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
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import ContextCenterRouter from './ContextCenterRouter';

jest.mock(
  '../../../pages/ContextCenterPage/ContextCenterDashboardPage/ContextCenterDashboardPage',
  () => jest.fn(() => <div>ContextCenterDashboardPage</div>)
);

jest.mock(
  '../../../pages/ContextCenterPage/AgentHarnessPage/AgentHarnessListPage',
  () => jest.fn(() => <div>AgentHarnessListPage</div>)
);

jest.mock(
  '../../../pages/ContextCenterPage/AgentHarnessPage/AgentHarnessEditorPage',
  () => jest.fn(() => <div>AgentHarnessEditorPage</div>)
);

jest.mock('react-router-dom', () => ({
  ...jest.requireActual('react-router-dom'),
  Navigate: jest.fn(({ to }) => <div>Redirected to {to}</div>),
}));

describe('ContextCenterRouter', () => {
  it('should render the overview page for the overview route', async () => {
    render(
      <MemoryRouter initialEntries={['/overview']}>
        <ContextCenterRouter />
      </MemoryRouter>
    );

    expect(
      await screen.findByText('ContextCenterDashboardPage')
    ).toBeInTheDocument();
  });

  it('should redirect an unknown context center path to the not found page', () => {
    render(
      <MemoryRouter initialEntries={['/integrations']}>
        <ContextCenterRouter />
      </MemoryRouter>
    );

    expect(screen.getByText('Redirected to /404')).toBeInTheDocument();
  });

  it.each([
    ['/agent-harness', 'AgentHarnessListPage'],
    ['/agent-harness/default', 'AgentHarnessEditorPage'],
    ['/agent-harness/persona/DataEngineer', 'AgentHarnessEditorPage'],
  ])('should render the agent harness page for %s', async (path, page) => {
    render(
      <MemoryRouter initialEntries={[path]}>
        <ContextCenterRouter />
      </MemoryRouter>
    );

    expect(await screen.findByText(page)).toBeInTheDocument();
  });

  it.each([
    ['/ai-context', '/context-center/agent-harness'],
    [
      '/ai-context/Data%20Engineer',
      '/context-center/agent-harness/persona/Data%20Engineer',
    ],
    [
      '/ai-context/%22team.analyst%22',
      '/context-center/agent-harness/persona/%22team.analyst%22',
    ],
  ])('should redirect the old AI Context link %s to %s', (path, target) => {
    render(
      <MemoryRouter initialEntries={[path]}>
        <ContextCenterRouter />
      </MemoryRouter>
    );

    expect(screen.getByText(`Redirected to ${target}`)).toBeInTheDocument();
  });
});
