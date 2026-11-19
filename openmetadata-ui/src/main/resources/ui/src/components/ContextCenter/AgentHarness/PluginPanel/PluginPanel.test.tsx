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
import { AgentHarnessManagedSettings } from '../../../../generated/api/context/agentHarnessManagedSettings';
import { getAgentHarnessPlugin } from '../../../../rest/AgentHarnessAPI';
import { downloadBlob } from '../../../../utils/ContextCenterPureUtils';
import PluginPanel from './PluginPanel';

jest.mock('../../../../rest/AgentHarnessAPI', () => ({
  getAgentHarnessPlugin: jest.fn(),
}));

jest.mock('../../../../utils/ContextCenterPureUtils', () => ({
  downloadBlob: jest.fn(),
}));

const SETTINGS: AgentHarnessManagedSettings = {
  claudeCode: { deniedMcpServers: [{ serverName: 'shadow-db' }] },
  codexRequirementsToml: '',
  notes: ['Entries limited to some tools are enforced by the hooks only.'],
  serverName: 'openmetadata',
  serverUrl: 'https://om.example.com/mcp',
};

const target = (id: string) =>
  within(screen.getByTestId('plugin-target')).getByTestId(
    `plugin-target-${id}`
  );

describe('PluginPanel', () => {
  afterEach(() => {
    jest.useRealTimers();
  });

  it('lists what the zip holds and what OpenMetadata serves at run time', () => {
    render(<PluginPanel settings={SETTINGS} />);

    expect(screen.getByTestId('plugin-file-tree').textContent).toBe(
      [
        'openmetadata.zip',
        '  .claude-plugin/plugin.json',
        '  .codex-plugin/plugin.json',
        '  .mcp.json',
        '  hooks/hooks.json',
        '  skills/openmetadata/SKILL.md',
      ].join('\n')
    );
    expect(
      screen.getByText('message.agent-harness-plugin-served-at-run-time')
    ).toBeInTheDocument();
  });

  it('downloads the Claude plugin and shows Downloaded for about 1.8 s', async () => {
    jest.useFakeTimers();
    const zip = new Blob(['zip']);
    (getAgentHarnessPlugin as jest.Mock).mockResolvedValue(zip);
    render(<PluginPanel settings={SETTINGS} />);

    await act(async () => {
      fireEvent.click(screen.getByTestId('download-plugin'));
    });

    expect(downloadBlob).toHaveBeenCalledWith(zip, 'openmetadata.zip');
    expect(screen.getByTestId('download-plugin')).toHaveTextContent(
      'label.downloaded'
    );

    act(() => {
      jest.advanceTimersByTime(1800);
    });

    expect(screen.getByTestId('download-plugin')).toHaveTextContent(
      'label.download-plugin'
    );
  });

  it('shows the ChatGPT connector URL and no file to download', () => {
    render(<PluginPanel settings={SETTINGS} />);

    fireEvent.click(target('chatGpt'));

    expect(screen.getByTestId('chatgpt-connector-url')).toHaveTextContent(
      'https://om.example.com/mcp'
    );
    expect(screen.queryByTestId('download-plugin')).not.toBeInTheDocument();
  });

  it('gives Codex and Claude Code commands and the managed settings to copy', () => {
    render(<PluginPanel settings={SETTINGS} />);

    fireEvent.click(target('cli'));

    expect(screen.getByTestId('codex-command')).toHaveTextContent(
      'codex mcp add openmetadata --url https://om.example.com/mcp codex mcp login openmetadata'
    );
    expect(screen.getByTestId('claude-code-command')).toHaveTextContent(
      'claude --plugin-dir ./openmetadata'
    );
    expect(
      JSON.parse(
        screen.getByTestId('claude-managed-settings').textContent ?? ''
      )
    ).toEqual(SETTINGS.claudeCode);
    expect(screen.getByTestId('codex-requirements-empty')).toBeInTheDocument();
    expect(screen.getByText(SETTINGS.notes[0])).toBeInTheDocument();
    expect(screen.getByTestId('codex-command-copy')).toBeInTheDocument();
  });

  it('shows the Codex requirements when the default denies unlisted servers', () => {
    render(
      <PluginPanel
        settings={{
          ...SETTINGS,
          codexRequirementsToml: '[mcp_servers.openmetadata]\n',
        }}
      />
    );

    fireEvent.click(target('cli'));

    expect(screen.getByTestId('codex-requirements')).toHaveTextContent(
      '[mcp_servers.openmetadata]'
    );
  });
});
