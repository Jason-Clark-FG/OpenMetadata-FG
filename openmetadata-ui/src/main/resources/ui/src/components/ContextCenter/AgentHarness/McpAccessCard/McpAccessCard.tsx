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
  Autocomplete,
  Badge,
  Box,
  Button,
  ButtonUtility,
  Card,
  Input,
  Select,
  SelectItemType,
  Typography,
} from '@openmetadata/ui-core-components';
import { Plus, Trash01 } from '@openmetadata/ui-core-components/icons';
import { useMemo } from 'react';
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
import {
  McpConnection,
  ToolAccessErrors,
} from '../../../../interface/discovery/agentHarness.interface';
import {
  allowedAccessLevels,
  minServerLevel,
  withToolAccess,
} from '../../../../utils/discovery/context-center/agentHarness.utils';
import SegmentedControl from '../SegmentedControl/SegmentedControl';
import {
  filterConnections,
  findConnection,
  toggleTool,
  uncoveredConnections,
} from './McpAccessCard.utils';

export interface McpAccessCardProps {
  access?: ToolAccess;
  /** The default's access on a persona harness: shown locked, and the floor for every entry. */
  inherited?: ToolAccess;
  isPersona: boolean;
  showInherited: boolean;
  errors: ToolAccessErrors;
  /** Outside MCP servers registered here, offered as rule targets. Empty leaves the card free text only. */
  connections?: McpConnection[];
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
  connections = [],
  canEdit,
  onChange,
}: McpAccessCardProps) => {
  const { t } = useTranslation();
  const floor = isPersona ? inherited : undefined;
  const unlisted = access?.default ?? floor?.default ?? ToolAccessDefault.Allow;
  const servers = access?.servers ?? [];
  const inheritedServers = isPersona && showInherited ? inherited?.servers : [];
  const hasConnections = connections.length > 0;
  const toolItems = useMemo(
    () =>
      new Map<string, SelectItemType[]>(
        connections.map(({ name, tools }) => [
          name,
          tools.map((tool) => ({
            id: tool.name,
            label: tool.name,
            supportingText: tool.description,
          })),
        ])
      ),
    [connections]
  );

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

  const updateServer = (
    index: number,
    change: (server: ToolAccessServer) => ToolAccessServer
  ) =>
    edit((current) => ({
      ...current,
      servers: (current.servers ?? []).map((server, at) =>
        at === index ? change(server) : server
      ),
    }));

  const editServer = (index: number, entry: ToolAccessServer) =>
    updateServer(index, () => entry);

  const setTool = (index: number, tool: string, selected: boolean) =>
    updateServer(index, (server) => ({
      ...server,
      tools: toggleTool(server.tools, tool, selected),
    }));

  const renderMatchField = (entry: ToolAccessServer, index: number) => {
    const error = errors.servers[index];
    const hint = error ? t(TOOL_ACCESS_ERROR_KEYS[error]) : undefined;

    if (!hasConnections) {
      return (
        <Input
          aria-label={t('label.server')}
          className="tw:w-48"
          hint={hint}
          inputClassName="tw:font-mono"
          inputDataTestId={`mcp-server-match-${index}`}
          isDisabled={!canEdit}
          isInvalid={Boolean(error)}
          placeholder={t('message.agent-harness-mcp-server-placeholder')}
          size="sm"
          value={entry.match}
          onChange={(match) => editServer(index, { ...entry, match })}
        />
      );
    }

    // Free text stays allowed: host server names are not known here.
    return (
      <Select.ComboBox
        allowsCustomValue
        aria-label={t('label.server')}
        className="tw:w-48"
        data-testid={`mcp-server-match-${index}`}
        fontSize="sm"
        hint={hint}
        inputValue={entry.match}
        isDisabled={!canEdit}
        isInvalid={Boolean(error)}
        items={filterConnections(connections, entry.match).map(
          ({ name, displayName }) => ({ id: name, label: displayName })
        )}
        placeholder={t('message.agent-harness-mcp-server-placeholder')}
        shortcut={false}
        showSearchIcon={false}
        size="sm"
        onInputChange={(match) =>
          // React Aria echoes the picked item's text back; skip the no-op.
          match !== entry.match &&
          updateServer(index, (server) => ({ ...server, match }))
        }
        onSelectionChange={(key) =>
          key !== null &&
          updateServer(index, (server) => ({ ...server, match: String(key) }))
        }>
        {(item) => (
          <Select.Item
            data-testid={`mcp-connection-option-${item.id}`}
            id={item.id}
            key={item.id}
            label={item.label}
            supportingText={item.id}
            // The input shows the name that goes into `match`, not the display name.
            textValue={item.id}
            value={{ id: item.id, label: item.label }}
          />
        )}
      </Select.ComboBox>
    );
  };

  const renderToolsField = (
    entry: ToolAccessServer,
    index: number,
    connection?: McpConnection
  ) => {
    if (!connection) {
      return (
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
      );
    }

    return (
      <div
        className="tw:min-w-48 tw:flex-1"
        data-testid={`mcp-server-tool-picker-${index}`}>
        <Autocomplete
          allowsCreation
          aria-label={t('label.tool-plural')}
          icon={null}
          isDisabled={!canEdit}
          items={toolItems.get(connection.name) ?? []}
          placeholder={t('message.agent-harness-mcp-all-tools')}
          selectedItems={(entry.tools ?? []).map((tool) => ({
            id: tool,
            label: tool,
          }))}
          onItemCleared={(key) => setTool(index, String(key), false)}
          onItemInserted={(key) => setTool(index, String(key), true)}>
          {(item) => (
            <Autocomplete.Item
              data-testid={`mcp-tool-option-${item.id}`}
              id={item.id}
              key={item.id}
              label={item.label}
              supportingText={item.supportingText}
            />
          )}
        </Autocomplete>
      </div>
    );
  };

  const renderServer = (entry: ToolAccessServer, index: number) => {
    const minLevel = floor ? minServerLevel(floor, entry) : undefined;
    const allowed = minLevel
      ? allowedAccessLevels(minLevel)
      : TOOL_ACCESS_LEVEL_ORDER;
    const connection = findConnection(connections, entry.match);

    return (
      <Box
        align="start"
        className="tw:border-t tw:border-secondary tw:px-4 tw:py-3"
        data-testid={`mcp-server-${index}`}
        gap={3}
        key={index}
        wrap="wrap">
        {hasConnections ? (
          <Box align="start" direction="col" gap={1}>
            {renderMatchField(entry, index)}
            {connection && (
              <Badge
                color="brand"
                data-testid={`mcp-server-registered-${index}`}
                size="sm">
                {t('label.registered-connection')}
              </Badge>
            )}
          </Box>
        ) : (
          renderMatchField(entry, index)
        )}
        {renderToolsField(entry, index, connection)}
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

  const renderCoverage = () =>
    hasConnections && (
      <Typography
        as="p"
        className="tw:px-4 tw:pt-3 tw:text-tertiary"
        data-testid="mcp-access-coverage"
        size="text-sm">
        {t('message.agent-harness-mcp-access-coverage')}
      </Typography>
    );

  // A default rule covers its connection on a persona harness too, shown or not.
  const renderUncovered = () => {
    const uncovered = uncoveredConnections(connections, [
      ...(floor?.servers ?? []),
      ...servers,
    ]);

    return (
      uncovered.length > 0 && (
        <Typography
          as="p"
          className="tw:border-t tw:border-secondary tw:px-4 tw:py-3 tw:text-tertiary"
          data-testid="mcp-uncovered-connections"
          size="text-sm">
          {t('message.agent-harness-mcp-uncovered-connections', {
            connections: uncovered
              .map(({ displayName }) => displayName)
              .join(', '),
            default: t(TOOL_ACCESS_DEFAULT_LABEL_KEYS[unlisted]),
          })}
        </Typography>
      )
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
      {renderCoverage()}
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
      {renderUncovered()}
    </Card>
  );
};

export default McpAccessCard;
