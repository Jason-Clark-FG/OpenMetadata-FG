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
  ButtonUtility,
  Card,
  Input,
  Typography,
} from '@openmetadata/ui-core-components';
import { Plus, Trash01 } from '@openmetadata/ui-core-components/icons';
import { useTranslation } from 'react-i18next';
import {
  TOOL_ACCESS_DEFAULT_LABEL_KEYS,
  TOOL_ACCESS_ERROR_KEYS,
  TOOL_ACCESS_LEVEL_LABEL_KEYS,
  TOOL_ACCESS_LEVEL_ORDER,
} from '../../../../constants/discovery/agentHarness.constants';
import {
  AgentHarnessSpec,
  ToolAccess,
  ToolAccessDefault,
  ToolAccessLevel,
  ToolAccessServer,
} from '../../../../generated/api/context/agentHarnessState';
import { ToolAccessErrors } from '../../../../interface/discovery/agentHarness.interface';
import {
  allowedAccessLevels,
  minServerLevel,
  withToolAccess,
} from '../../../../utils/discovery/context-center/agentHarness.utils';
import SegmentedControl from '../SegmentedControl/SegmentedControl';

export interface McpAccessCardProps {
  access?: ToolAccess;
  /** The default's access on a persona harness: shown locked, and the floor for every entry. */
  inherited?: ToolAccess;
  isPersona: boolean;
  showInherited: boolean;
  errors: ToolAccessErrors;
  canEdit: boolean;
  onChange: (change: (spec: AgentHarnessSpec) => AgentHarnessSpec) => void;
}

const parseTools = (text: string): string[] | undefined => {
  const tools = text
    .split(',')
    .map((tool) => tool.trim())
    .filter(Boolean);

  return tools.length ? tools : undefined;
};

const McpAccessCard = ({
  access,
  inherited,
  isPersona,
  showInherited,
  errors,
  canEdit,
  onChange,
}: McpAccessCardProps) => {
  const { t } = useTranslation();
  const floor = isPersona ? inherited : undefined;
  const unlisted = access?.default ?? floor?.default ?? ToolAccessDefault.Allow;
  const servers = access?.servers ?? [];
  const inheritedServers = isPersona && showInherited ? inherited?.servers : [];

  const edit = (change: (current: ToolAccess) => ToolAccess) =>
    onChange((spec) =>
      withToolAccess(
        spec,
        change(
          spec.toolAccess ?? {
            default: floor?.default ?? ToolAccessDefault.Allow,
            servers: [],
          }
        ),
        floor
      )
    );

  const editServer = (index: number, entry: ToolAccessServer) =>
    edit((current) => ({
      ...current,
      servers: (current.servers ?? []).map((server, at) =>
        at === index ? entry : server
      ),
    }));

  const renderServer = (entry: ToolAccessServer, index: number) => {
    const minLevel = floor ? minServerLevel(floor, entry) : undefined;
    const allowed = minLevel
      ? allowedAccessLevels(minLevel)
      : TOOL_ACCESS_LEVEL_ORDER;
    const error = errors.servers[index];

    return (
      <Box
        align="start"
        className="tw:border-t tw:border-secondary tw:px-4 tw:py-3"
        data-testid={`mcp-server-${index}`}
        gap={3}
        key={index}
        wrap="wrap">
        <Input
          aria-label={t('label.server')}
          className="tw:w-48"
          hint={error ? t(TOOL_ACCESS_ERROR_KEYS[error]) : undefined}
          inputClassName="tw:font-mono"
          inputDataTestId={`mcp-server-match-${index}`}
          isDisabled={!canEdit}
          isInvalid={Boolean(error)}
          placeholder={t('message.agent-harness-mcp-server-placeholder')}
          size="sm"
          value={entry.match}
          onChange={(match) => editServer(index, { ...entry, match })}
        />
        <Input
          aria-label={t('label.tool-plural')}
          className="tw:min-w-48 tw:flex-1"
          defaultValue={entry.tools?.join(', ') ?? ''}
          inputClassName="tw:font-mono"
          inputDataTestId={`mcp-server-tools-${index}`}
          isDisabled={!canEdit}
          key={`${index}:${entry.tools?.join(',') ?? ''}`}
          placeholder={t('message.agent-harness-mcp-all-tools')}
          size="sm"
          onBlur={(event) =>
            editServer(index, {
              ...entry,
              tools: parseTools((event.target as HTMLInputElement).value),
            })
          }
        />
        <SegmentedControl
          aria-label={t('label.level')}
          data-testid={`mcp-server-level-${index}`}
          isDisabled={!canEdit}
          options={TOOL_ACCESS_LEVEL_ORDER.map((level) => ({
            id: level,
            isDisabled: !allowed.includes(level),
            label: t(TOOL_ACCESS_LEVEL_LABEL_KEYS[level]),
          }))}
          value={entry.level}
          onChange={(level: ToolAccessLevel) =>
            editServer(index, { ...entry, level })
          }
        />
        {canEdit && (
          <ButtonUtility
            color="tertiary"
            data-testid={`mcp-server-remove-${index}`}
            icon={Trash01}
            size="sm"
            tooltip={t('label.remove')}
            onClick={() =>
              edit((current) => ({
                ...current,
                servers: (current.servers ?? []).filter(
                  (_, at) => at !== index
                ),
              }))
            }
          />
        )}
      </Box>
    );
  };

  return (
    <Card data-testid="mcp-access">
      <Card.Header
        extra={
          canEdit && (
            <Button
              color="secondary"
              data-testid="add-mcp-server"
              iconLeading={Plus}
              size="sm"
              onPress={() =>
                edit((current) => ({
                  ...current,
                  servers: [
                    ...(current.servers ?? []),
                    { level: ToolAccessLevel.Block, match: '' },
                  ],
                }))
              }>
              {t('label.add-server')}
            </Button>
          )
        }
        subtitle={t(
          isPersona
            ? 'message.agent-harness-mcp-access-persona-intro'
            : 'message.agent-harness-mcp-access-intro'
        )}
        title={t('label.mcp-access')}
      />
      <Box align="center" className="tw:px-4 tw:py-3" gap={3} wrap="wrap">
        <Typography className="tw:flex-1" size="text-sm" weight="medium">
          {t('label.unlisted-server-plural')}
        </Typography>
        <SegmentedControl
          aria-label={t('label.unlisted-server-plural')}
          data-testid="mcp-default"
          isDisabled={!canEdit}
          options={[ToolAccessDefault.Allow, ToolAccessDefault.Deny].map(
            (value) => ({
              id: value,
              isDisabled:
                value === ToolAccessDefault.Allow &&
                floor?.default === ToolAccessDefault.Deny,
              label: t(TOOL_ACCESS_DEFAULT_LABEL_KEYS[value]),
            })
          )}
          value={unlisted}
          onChange={(value: ToolAccessDefault) =>
            edit((current) => ({ ...current, default: value }))
          }
        />
      </Box>
      {inheritedServers?.map((entry) => (
        <Box
          align="center"
          className="tw:border-t tw:border-secondary tw:bg-secondary tw:px-4 tw:py-3"
          data-testid={`mcp-inherited-${entry.match}`}
          gap={3}
          key={`${entry.match}|${entry.tools?.join(',') ?? ''}`}
          wrap="wrap">
          <Typography className="tw:w-48 tw:font-mono" size="text-sm">
            {entry.match}
          </Typography>
          <Typography
            className="tw:min-w-48 tw:flex-1 tw:font-mono tw:text-tertiary"
            size="text-sm">
            {entry.tools?.join(', ') ??
              t('message.agent-harness-mcp-all-tools')}
          </Typography>
          <Badge color="gray" size="sm">
            {t(TOOL_ACCESS_LEVEL_LABEL_KEYS[entry.level])}
          </Badge>
          <Badge color="gray" size="sm">
            {t('label.inherited-locked')}
          </Badge>
        </Box>
      ))}
      {servers.map(renderServer)}
    </Card>
  );
};

export default McpAccessCard;
