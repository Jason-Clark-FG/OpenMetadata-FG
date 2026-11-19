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
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { getAgentHarnessPlugin } from '../../../../rest/AgentHarnessAPI';
import { downloadBlob } from '../../../../utils/ContextCenterPureUtils';
import ConnectAiToolsModal from './ConnectAiToolsModal';

jest.mock('../../../../rest/AgentHarnessAPI', () => ({
  getAgentHarnessPlugin: jest.fn(),
}));
jest.mock('../../../../utils/ContextCenterPureUtils', () => ({
  downloadBlob: jest.fn(),
}));
jest.mock('../../../../utils/ToastUtils', () => ({
  showErrorToast: jest.fn(),
}));

describe('ConnectAiToolsModal', () => {
  it('shows the Claude Code and Codex install commands for this server', () => {
    render(<ConnectAiToolsModal onClose={jest.fn()} />);

    expect(screen.getByTestId('claude-code-command')).toHaveTextContent(
      'unzip openmetadata.zip -d openmetadata claude --plugin-dir ./openmetadata'
    );
    expect(screen.getByTestId('codex-command')).toHaveTextContent(
      `codex mcp add openmetadata --url ${window.location.origin}/mcp`
    );
  });

  it('downloads the plugin zip with the signed-in user credentials', async () => {
    const zip = new Blob(['zip']);
    (getAgentHarnessPlugin as jest.Mock).mockResolvedValue(zip);
    render(<ConnectAiToolsModal onClose={jest.fn()} />);

    fireEvent.click(screen.getByTestId('download-plugin'));

    await waitFor(() =>
      expect(downloadBlob).toHaveBeenCalledWith(zip, 'openmetadata.zip')
    );
  });

  it('calls onClose from the close button', () => {
    const onClose = jest.fn();
    render(<ConnectAiToolsModal onClose={onClose} />);

    fireEvent.click(screen.getByTestId('connect-ai-tools-close'));

    expect(onClose).toHaveBeenCalled();
  });
});
