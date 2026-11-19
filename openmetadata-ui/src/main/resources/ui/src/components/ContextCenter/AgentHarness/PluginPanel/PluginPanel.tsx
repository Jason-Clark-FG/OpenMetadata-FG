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
  Badge,
  Box,
  Button,
  Card,
  Skeleton,
  Typography,
} from '@openmetadata/ui-core-components';
import { Check, Download01 } from '@openmetadata/ui-core-components/icons';
import { AxiosError } from 'axios';
import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  CLAUDE_MANAGED_SETTINGS_FILE,
  CODEX_REQUIREMENTS_FILE,
  DONE_LABEL_MS,
  PLUGIN_INCLUDE_KEYS,
  PLUGIN_TARGETS,
  PLUGIN_ZIP_ENTRIES,
  PLUGIN_ZIP_NAME,
} from '../../../../constants/discovery/agentHarness.constants';
import { AgentHarnessManagedSettings } from '../../../../generated/api/context/agentHarnessManagedSettings';
import { SurfaceID } from '../../../../generated/api/context/agentHarnessSurfaces';
import { PluginTarget } from '../../../../interface/discovery/agentHarness.interface';
import { getAgentHarnessPlugin } from '../../../../rest/AgentHarnessAPI';
import { downloadBlob } from '../../../../utils/ContextCenterPureUtils';
import { getInstallCommand } from '../../../../utils/discovery/context-center/agentHarness.utils';
import { showErrorToast } from '../../../../utils/ToastUtils';
import CopyButton from '../../../common/CopyButton/CopyButton';
import SegmentedControl from '../SegmentedControl/SegmentedControl';

export interface PluginPanelProps {
  /** Server URL and host settings; undefined while they load. */
  settings?: AgentHarnessManagedSettings;
}

const PluginPanel = ({ settings }: PluginPanelProps) => {
  const { t } = useTranslation();
  const [target, setTarget] = useState(PluginTarget.Claude);
  const [download, setDownload] = useState<'idle' | 'loading' | 'done'>('idle');
  const resetTimer = useRef<number>();

  useEffect(() => () => window.clearTimeout(resetTimer.current), []);

  const handleDownload = async () => {
    setDownload('loading');
    try {
      downloadBlob(await getAgentHarnessPlugin(), PLUGIN_ZIP_NAME);
      setDownload('done');
      resetTimer.current = window.setTimeout(
        () => setDownload('idle'),
        DONE_LABEL_MS
      );
    } catch (error) {
      setDownload('idle');
      showErrorToast(error as AxiosError);
    }
  };

  const renderCode = (code: string, testId: string, copyLabel?: string) => (
    <Box className="tw:relative" direction="col">
      <pre
        className="tw:m-0 tw:max-h-80 tw:overflow-auto tw:rounded-lg tw:bg-primary-solid tw:p-4 tw:pr-14 tw:font-mono tw:text-xs tw:text-white tw:whitespace-pre-wrap tw:break-all"
        data-testid={testId}>
        {code}
      </pre>
      <div className="tw:absolute tw:right-2 tw:top-2">
        <CopyButton
          color="secondary"
          size="sm"
          testId={`${testId}-copy`}
          tooltip={copyLabel ?? t('label.copy')}
          value={code}
        />
      </div>
    </Box>
  );

  const renderSteps = (stepKeys: string[]) => (
    <ol className="tw:m-0 tw:flex tw:flex-col tw:gap-1 tw:pl-5">
      {stepKeys.map((key) => (
        <li className="tw:text-sm tw:text-secondary" key={key}>
          {t(key)}
        </li>
      ))}
    </ol>
  );

  const renderHeading = (titleKey: string, whoKey: string) => (
    <Box direction="col" gap={1}>
      <Typography size="text-md" weight="semibold">
        {t(titleKey)}
      </Typography>
      <Typography className="tw:text-tertiary" size="text-sm">
        {t(whoKey)}
      </Typography>
    </Box>
  );

  const renderIncludes = (keys: string[]) => (
    <Box align="center" gap={2} wrap="wrap">
      <Typography className="tw:text-tertiary" size="text-sm">
        {t('label.includes')}
      </Typography>
      {keys.map((key) => (
        <Badge color="gray" key={key} size="sm">
          {t(key)}
        </Badge>
      ))}
    </Box>
  );

  const renderClaude = () => (
    <Box data-testid="plugin-target-claude" direction="col" gap={4}>
      {renderHeading(
        'message.agent-harness-plugin-claude-title',
        'message.agent-harness-plugin-claude-who'
      )}
      {renderSteps([
        'message.agent-harness-plugin-claude-step-download',
        'message.agent-harness-plugin-claude-step-upload',
        'message.agent-harness-plugin-claude-step-connect',
      ])}
      <pre
        className="tw:m-0 tw:rounded-lg tw:bg-primary-solid tw:p-4 tw:font-mono tw:text-xs tw:text-white"
        data-testid="plugin-file-tree">
        {[
          PLUGIN_ZIP_NAME,
          ...PLUGIN_ZIP_ENTRIES.map((entry) => `  ${entry}`),
        ].join('\n')}
      </pre>
      {renderIncludes(PLUGIN_INCLUDE_KEYS)}
      <Typography className="tw:text-tertiary" size="text-sm">
        {t('message.agent-harness-plugin-served-at-run-time')}
      </Typography>
      <Box>
        <Button
          color="primary"
          data-testid="download-plugin"
          iconLeading={download === 'done' ? Check : Download01}
          isLoading={download === 'loading'}
          size="sm"
          onPress={handleDownload}>
          {t(
            download === 'done' ? 'label.downloaded' : 'label.download-plugin'
          )}
        </Button>
      </Box>
    </Box>
  );

  const renderChatGpt = () => (
    <Box data-testid="plugin-target-chatgpt" direction="col" gap={4}>
      {renderHeading(
        'message.agent-harness-plugin-chatgpt-title',
        'message.agent-harness-plugin-chatgpt-who'
      )}
      {renderSteps([
        'message.agent-harness-plugin-chatgpt-step-create',
        'message.agent-harness-plugin-chatgpt-step-url',
        'message.agent-harness-plugin-chatgpt-step-publish',
      ])}
      {settings ? (
        <>
          <Typography size="text-sm" weight="semibold">
            {t('label.connector-url')}
          </Typography>
          {renderCode(
            settings.serverUrl,
            'chatgpt-connector-url',
            t('label.copy-item', { item: t('label.connector-url') })
          )}
          <Typography size="text-sm" weight="semibold">
            {t('label.authentication')}
          </Typography>
          <Typography className="tw:text-secondary" size="text-sm">
            {t('message.agent-harness-plugin-chatgpt-oauth', {
              url: new URL(
                '/.well-known/oauth-protected-resource',
                settings.serverUrl
              ).toString(),
            })}
          </Typography>
        </>
      ) : (
        <Skeleton className="tw:h-20 tw:w-full" />
      )}
      {renderIncludes(['label.mcp'])}
      <Typography className="tw:text-tertiary" size="text-sm">
        {t('message.agent-harness-plugin-chatgpt-no-file')}
      </Typography>
    </Box>
  );

  const renderCli = () => {
    if (!settings) {
      return <Skeleton className="tw:h-40 tw:w-full" />;
    }
    const toml = settings.codexRequirementsToml.trim();

    return (
      <Box data-testid="plugin-target-cli" direction="col" gap={4}>
        {renderHeading(
          'message.agent-harness-plugin-cli-title',
          'message.agent-harness-plugin-cli-who'
        )}
        <Typography size="text-sm" weight="semibold">
          {t('label.claude-code')}
        </Typography>
        <Typography className="tw:text-tertiary" size="text-sm">
          {t('message.agent-harness-plugin-claude-code-step')}
        </Typography>
        {renderCode(
          getInstallCommand(SurfaceID.ClaudeCode, settings) ?? '',
          'claude-code-command'
        )}
        <Typography size="text-sm" weight="semibold">
          {t('label.codex')}
        </Typography>
        {renderCode(
          getInstallCommand(SurfaceID.Codex, settings) ?? '',
          'codex-command'
        )}
        <Typography size="text-sm" weight="semibold">
          {t('label.managed-setting-plural')}
        </Typography>
        <Typography className="tw:text-tertiary" size="text-sm">
          {t('message.agent-harness-managed-settings-hint')}
        </Typography>
        <Typography className="tw:font-mono" size="text-xs">
          {CLAUDE_MANAGED_SETTINGS_FILE}
        </Typography>
        {renderCode(
          JSON.stringify(settings.claudeCode, null, 2),
          'claude-managed-settings'
        )}
        <Typography className="tw:font-mono" size="text-xs">
          {CODEX_REQUIREMENTS_FILE}
        </Typography>
        {toml ? (
          renderCode(toml, 'codex-requirements')
        ) : (
          <Typography
            className="tw:text-tertiary"
            data-testid="codex-requirements-empty"
            size="text-sm">
            {t('message.agent-harness-codex-requirements-empty')}
          </Typography>
        )}
        {settings.notes.map((note) => (
          <Typography className="tw:text-tertiary" key={note} size="text-sm">
            {note}
          </Typography>
        ))}
      </Box>
    );
  };

  const renderTarget = () => {
    switch (target) {
      case PluginTarget.ChatGpt:
        return renderChatGpt();
      case PluginTarget.Cli:
        return renderCli();
      default:
        return renderClaude();
    }
  };

  return (
    <Card data-testid="plugin-panel">
      <Card.Header
        subtitle={t('message.agent-harness-plugin-panel-subtitle')}
        title={PLUGIN_ZIP_NAME}
      />
      <div className="tw:grid tw:grid-cols-[repeat(auto-fit,minmax(200px,1fr))] tw:border-b tw:border-secondary">
        {[
          {
            descriptionKey: 'message.agent-harness-plugin-part-skill',
            file: 'skills/openmetadata/SKILL.md',
            labelKey: 'label.skill',
          },
          {
            descriptionKey: 'message.agent-harness-plugin-part-mcp',
            file: '.mcp.json',
            labelKey: 'label.mcp',
          },
          {
            descriptionKey: 'message.agent-harness-plugin-part-hooks',
            file: 'hooks/hooks.json',
            labelKey: 'label.hook-plural',
          },
        ].map((part) => (
          <Box
            className="tw:border-r tw:border-secondary tw:px-4 tw:py-3 tw:last:border-r-0"
            direction="col"
            gap={1}
            key={part.file}>
            <Typography size="text-sm" weight="semibold">
              {t(part.labelKey)}
            </Typography>
            <Typography
              className="tw:font-mono tw:text-tertiary"
              size="text-xs">
              {part.file}
            </Typography>
            <Typography className="tw:text-tertiary" size="text-sm">
              {t(part.descriptionKey)}
            </Typography>
          </Box>
        ))}
      </div>
      <Card.Content>
        <Box direction="col" gap={4}>
          <Box align="center" gap={3} wrap="wrap">
            <Typography size="text-sm" weight="medium">
              {t('label.install-on')}
            </Typography>
            <SegmentedControl
              aria-label={t('label.install-on')}
              data-testid="plugin-target"
              options={PLUGIN_TARGETS.map((option) => ({
                id: option.target,
                label: t(option.labelKey),
              }))}
              value={target}
              onChange={setTarget}
            />
          </Box>
          {renderTarget()}
          <Typography className="tw:text-tertiary" size="text-sm">
            {t('message.agent-harness-plugin-persona-at-sign-in')}
          </Typography>
        </Box>
      </Card.Content>
    </Card>
  );
};

export default PluginPanel;
