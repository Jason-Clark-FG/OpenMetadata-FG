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
  Box,
  Button,
  Dialog,
  Modal,
  ModalOverlay,
  Typography,
} from '@openmetadata/ui-core-components';
import { useTranslation } from 'react-i18next';
import { SurfaceID } from '../../../../generated/api/context/agentHarnessSurfaces';
import { getInstallCommand } from '../../../../utils/discovery/context-center/agentHarness.utils';
import PluginCodeBlock from '../PluginPanel/PluginCodeBlock';
import PluginDownloadButton from '../PluginPanel/PluginDownloadButton';

const MODAL_WIDTH = 640;
const SERVER_NAME = 'openmetadata';

export interface ConnectAiToolsModalProps {
  onClose: () => void;
}

/**
 * The managed-settings endpoint is admin-only, so the install commands are built from the
 * address the user is already signed in at.
 */
const ConnectAiToolsModal = ({ onClose }: ConnectAiToolsModalProps) => {
  const { t } = useTranslation();
  const host = {
    serverName: SERVER_NAME,
    serverUrl: `${window.location.origin}/mcp`,
  };

  return (
    <ModalOverlay
      isDismissable
      isOpen
      onOpenChange={(isOpen) => !isOpen && onClose()}>
      <Modal>
        <Dialog
          data-testid="connect-ai-tools-modal"
          width={MODAL_WIDTH}
          onClose={onClose}>
          <Dialog.Header title={t('label.connect-your-ai-tools')} />
          <Dialog.Content>
            <Box direction="col" gap={4}>
              <Typography className="tw:text-tertiary" size="text-sm">
                {t('message.connect-ai-tools-description')}
              </Typography>
              <Typography size="text-sm" weight="semibold">
                {t('label.claude-code')}
              </Typography>
              <Typography className="tw:text-secondary" size="text-sm">
                {t('message.agent-harness-plugin-claude-step-download')}
              </Typography>
              <Box>
                <PluginDownloadButton />
              </Box>
              <Typography className="tw:text-secondary" size="text-sm">
                {t('message.agent-harness-plugin-claude-code-step')}
              </Typography>
              <PluginCodeBlock
                code={getInstallCommand(SurfaceID.ClaudeCode, host) ?? ''}
                testId="claude-code-command"
              />
              <Typography className="tw:text-secondary" size="text-sm">
                {t('message.connect-ai-tools-claude-code-sign-in')}
              </Typography>
              <Typography size="text-sm" weight="semibold">
                {t('label.codex')}
              </Typography>
              <PluginCodeBlock
                code={getInstallCommand(SurfaceID.Codex, host) ?? ''}
                testId="codex-command"
              />
              <Typography className="tw:text-secondary" size="text-sm">
                {t('message.connect-ai-tools-codex-sign-in')}
              </Typography>
            </Box>
          </Dialog.Content>
          <Dialog.Footer>
            <Button
              color="secondary"
              data-testid="connect-ai-tools-close"
              size="md"
              onPress={onClose}>
              {t('label.close')}
            </Button>
          </Dialog.Footer>
        </Dialog>
      </Modal>
    </ModalOverlay>
  );
};

export default ConnectAiToolsModal;
