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
import { fireEvent, render, screen } from '@testing-library/react';
import {
  SurfaceID,
  SurfaceStatus,
} from '../../../../generated/api/context/agentHarnessSurfaces';
import {
  getAgentHarnessManagedSettings,
  getAgentHarnessSurfaces,
} from '../../../../rest/AgentHarnessAPI';
import SurfacesTab from './SurfacesTab';

jest.mock('../../../../rest/AgentHarnessAPI', () => ({
  getAgentHarnessManagedSettings: jest.fn(),
  getAgentHarnessPlugin: jest.fn(),
  getAgentHarnessSurfaces: jest.fn(),
}));

describe('SurfacesTab', () => {
  beforeEach(() => {
    (getAgentHarnessSurfaces as jest.Mock).mockResolvedValue({
      surfaces: [
        {
          connector: true,
          hooks: true,
          id: SurfaceID.ClaudeCode,
          lastSeenAt: 1763460000000,
          name: 'Claude Code',
          skills: true,
          status: SurfaceStatus.Connected,
        },
        {
          connector: true,
          hooks: false,
          id: SurfaceID.ChatGPT,
          name: 'ChatGPT',
          skills: false,
          status: SurfaceStatus.NotConnected,
        },
      ],
    });
    (getAgentHarnessManagedSettings as jest.Mock).mockResolvedValue({
      claudeCode: {},
      codexRequirementsToml: '',
      notes: [],
      serverName: 'openmetadata',
      serverUrl: 'https://om.example.com/mcp',
    });
  });

  it('shows each surface with its channels and whether it has connected', async () => {
    render(<SurfacesTab />);

    expect(
      await screen.findByTestId('surface-status-claudeCode')
    ).toHaveTextContent('label.connected');
    expect(screen.getByTestId('surface-status-chatGpt')).toHaveTextContent(
      'label.not-connected'
    );
    expect(screen.getByTestId('surface-chatGpt')).toHaveTextContent('—');
  });

  it('opens and closes the plugin panel from the banner', async () => {
    render(<SurfacesTab />);

    const toggle = screen.getByTestId('generate-plugin');

    expect(screen.queryByTestId('plugin-panel')).not.toBeInTheDocument();

    fireEvent.click(toggle);

    expect(await screen.findByTestId('plugin-panel')).toBeInTheDocument();
    expect(toggle).toHaveTextContent('label.hide-content-plural');

    fireEvent.click(toggle);

    expect(screen.queryByTestId('plugin-panel')).not.toBeInTheDocument();
    expect(toggle).toHaveTextContent('label.generate-plugin');
  });
});
