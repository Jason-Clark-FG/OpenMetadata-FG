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
  Toggle,
  Typography,
} from '@openmetadata/ui-core-components';
import classNames from 'classnames';
import { useTranslation } from 'react-i18next';
import {
  AgentHarnessSpec,
  ConnectionSetting,
  HarnessKind,
  KnowledgeGraphSettings,
} from '../../../../generated/api/context/agentHarnessState';
import {
  McpConnection,
  SubAgentDefinition,
} from '../../../../interface/discovery/agentHarness.interface';
import agentHarnessConnectionsClassBase from '../../../../utils/AgentHarnessConnectionsClassBase';

export interface KnowledgeGraphTabProps {
  kind: HarnessKind;
  spec: AgentHarnessSpec;
  /** The settings this harness falls back to: the default's on a persona harness. */
  inheritedKnowledgeGraph?: KnowledgeGraphSettings;
  subAgents: SubAgentDefinition[];
  /** Outside MCP servers registered here, from the editor page's one request. */
  connections?: McpConnection[];
  canEdit: boolean;
  onChange: (change: (spec: AgentHarnessSpec) => AgentHarnessSpec) => void;
  /** Reads the registered connections again, after a distribution's piece changed them. */
  onConnectionsChange?: () => Promise<void> | void;
}

type KnowledgeGraphToggle = 'entry' | 'rawQueries' | 'learn';

export const KNOWLEDGE_GRAPH_DEFAULTS: Required<KnowledgeGraphSettings> = {
  entry: false,
  learn: true,
  connections: [],
  rawQueries: true,
  subAgents: [],
};

/** One row of the Connections section: a registered connection, or a switched-on name gone since. */
interface ConnectionRow {
  name: string;
  displayName: string;
  description?: string;
  tools?: number;
  registered: boolean;
}

const TOGGLES: {
  key: KnowledgeGraphToggle;
  labelKey: string;
  hintKey: string;
}[] = [
  {
    hintKey: 'message.agent-harness-kg-entry-hint',
    key: 'entry',
    labelKey: 'label.enter-through-knowledge-graph',
  },
  {
    hintKey: 'message.agent-harness-kg-raw-queries-hint',
    key: 'rawQueries',
    labelKey: 'label.allow-raw-sparql-query-plural',
  },
  {
    hintKey: 'message.agent-harness-kg-learn-hint',
    key: 'learn',
    labelKey: 'label.learn-from-turn-plural',
  },
];

// One stable empty list, so a piece that re-reads on a new list does not re-read every render.
const NO_CONNECTIONS: McpConnection[] = [];

const ROW_CLASS =
  'tw:border-t tw:border-secondary tw:px-4 tw:py-4 tw:first:border-t-0';

const KnowledgeGraphTab = ({
  kind,
  spec,
  inheritedKnowledgeGraph,
  subAgents,
  connections = NO_CONNECTIONS,
  canEdit,
  onChange,
  onConnectionsChange,
}: KnowledgeGraphTabProps) => {
  const { t } = useTranslation();
  const AddConnectionAction =
    agentHarnessConnectionsClassBase.getAddConnectionAction();
  const ConnectionRowAction =
    agentHarnessConnectionsClassBase.getConnectionRowAction();
  const PendingConnections =
    agentHarnessConnectionsClassBase.getPendingConnections();
  const isPersona = kind === HarnessKind.Persona;
  // A persona harness lists only the connections it adds; the default's stay on and are locked.
  const ownConnections: ConnectionSetting[] =
    spec.knowledgeGraph?.connections ?? [];
  const inheritedConnections: ConnectionSetting[] = isPersona
    ? inheritedKnowledgeGraph?.connections ?? []
    : [];
  const settings: Required<KnowledgeGraphSettings> = {
    ...KNOWLEDGE_GRAPH_DEFAULTS,
    ...inheritedKnowledgeGraph,
    ...spec.knowledgeGraph,
  };
  const usesDefault = kind === HarnessKind.Persona && !spec.knowledgeGraph;

  const set = (change: Partial<KnowledgeGraphSettings>) =>
    onChange((current) => ({
      ...current,
      knowledgeGraph: {
        ...KNOWLEDGE_GRAPH_DEFAULTS,
        ...inheritedKnowledgeGraph,
        ...current.knowledgeGraph,
        connections: current.knowledgeGraph?.connections ?? [],
        ...change,
      },
    }));

  const isOn = (list: ConnectionSetting[], name: string) =>
    list.some((connection) => connection.name === name && connection.enabled);

  const setConnection = (row: ConnectionRow, enabled: boolean) =>
    set({
      connections: [
        ...ownConnections.filter((connection) => connection.name !== row.name),
        // A name no longer registered is dropped when switched off rather than kept as off.
        ...(enabled || row.registered ? [{ enabled, name: row.name }] : []),
      ],
    });

  // Built from the draft as it is when the reload lands, not as it was when the piece was opened.
  const switchConnectionOn = (name: string) =>
    onChange((current) => ({
      ...current,
      knowledgeGraph: {
        ...KNOWLEDGE_GRAPH_DEFAULTS,
        ...inheritedKnowledgeGraph,
        ...current.knowledgeGraph,
        connections: [
          ...(current.knowledgeGraph?.connections ?? []).filter(
            (connection) => connection.name !== name
          ),
          { enabled: true, name },
        ],
      },
    }));

  const handleConnectionsChange = async (addedName?: string) => {
    await onConnectionsChange?.();
    if (addedName && canEdit && !isOn(inheritedConnections, addedName)) {
      switchConnectionOn(addedName);
    }
  };

  const renderAddConnection = () =>
    AddConnectionAction && (
      <AddConnectionAction
        canEdit={canEdit}
        onConnectionsChange={handleConnectionsChange}
      />
    );

  const renderPendingConnections = () =>
    PendingConnections && (
      <PendingConnections
        canEdit={canEdit}
        connections={connections}
        onConnectionsChange={handleConnectionsChange}
      />
    );

  const registeredNames = new Set(connections.map(({ name }) => name));
  const connectionRows: ConnectionRow[] = [
    ...connections.map((connection) => ({
      description: connection.description,
      displayName: connection.displayName || connection.name,
      name: connection.name,
      registered: true,
      tools: connection.tools.length,
    })),
    ...[
      ...new Set(
        [...inheritedConnections, ...ownConnections]
          .filter((connection) => connection.enabled)
          .map((connection) => connection.name)
      ),
    ]
      .filter((name) => !registeredNames.has(name))
      .map((name) => ({ displayName: name, name, registered: false })),
  ];

  const isAgentEnabled = (name: string) =>
    settings.subAgents.some((agent) => agent.name === name && agent.enabled);

  const setAgent = (name: string, enabled: boolean) =>
    set({
      subAgents: [
        ...settings.subAgents.filter((agent) => agent.name !== name),
        { enabled, name },
      ],
    });

  const renderConnection = (row: ConnectionRow) => {
    const inherited = isOn(inheritedConnections, row.name);

    return (
      <Box
        align="center"
        className={classNames(ROW_CLASS, {
          'tw:opacity-55': !row.registered,
        })}
        data-testid={`knowledge-graph-connection-row-${row.name}`}
        gap={4}
        key={row.name}>
        <Box className="tw:flex-1" direction="col" gap={1}>
          <Box align="center" gap={2} wrap="wrap">
            <Typography size="text-sm" weight="semibold">
              {row.displayName}
            </Typography>
            {row.registered ? (
              <Badge color="gray" size="sm">
                {t('message.agent-harness-kg-connection-tool-count', {
                  count: row.tools,
                })}
              </Badge>
            ) : (
              <Badge
                color="warning"
                data-testid={`knowledge-graph-connection-gone-${row.name}`}
                size="sm">
                {t('message.agent-harness-kg-connection-no-longer-registered')}
              </Badge>
            )}
            {inherited && (
              <Badge color="gray" size="sm">
                {t('label.from-default')}
              </Badge>
            )}
          </Box>
          {row.description && (
            <Typography className="tw:text-tertiary" size="text-sm">
              {row.description}
            </Typography>
          )}
        </Box>
        {row.registered && ConnectionRowAction && (
          <ConnectionRowAction
            canEdit={canEdit}
            connectionName={row.name}
            onConnectionsChange={handleConnectionsChange}
          />
        )}
        <Toggle
          aria-label={row.displayName}
          data-testid={`knowledge-graph-connection-${row.name}`}
          isDisabled={!canEdit || inherited}
          isSelected={inherited || isOn(ownConnections, row.name)}
          onChange={(value) => setConnection(row, value)}
        />
      </Box>
    );
  };

  return (
    <Box data-testid="knowledge-graph-tab" direction="col" gap={4}>
      {kind === HarnessKind.Persona && (
        <Box align="center" gap={3}>
          {usesDefault ? (
            <Badge
              color="gray"
              data-testid="knowledge-graph-from-default"
              size="sm">
              {t('label.from-default')}
            </Badge>
          ) : (
            canEdit && (
              <Button
                color="link-gray"
                data-testid="knowledge-graph-use-default"
                size="sm"
                onPress={() =>
                  onChange((current) => ({
                    ...current,
                    knowledgeGraph: undefined,
                  }))
                }>
                {t('label.use-default-setting-plural')}
              </Button>
            )
          )}
          <Typography className="tw:text-tertiary" size="text-sm">
            {t('message.agent-harness-kg-persona-hint')}
          </Typography>
        </Box>
      )}
      <Card data-testid="knowledge-graph-settings">
        {TOGGLES.map(({ key, labelKey, hintKey }) => (
          <Box align="center" className={ROW_CLASS} gap={4} key={key}>
            <Box className="tw:flex-1" direction="col" gap={1}>
              <Typography size="text-sm" weight="semibold">
                {t(labelKey)}
              </Typography>
              <Typography className="tw:text-tertiary" size="text-sm">
                {t(hintKey)}
              </Typography>
            </Box>
            <Toggle
              aria-label={t(labelKey)}
              data-testid={`knowledge-graph-${key}`}
              isDisabled={!canEdit}
              isSelected={settings[key]}
              onChange={(value) => set({ [key]: value })}
            />
          </Box>
        ))}
      </Card>
      <Box direction="col" gap={1}>
        <Typography size="text-sm" weight="semibold">
          {t('label.sub-agent-plural')}
        </Typography>
        <Typography className="tw:text-tertiary" size="text-sm">
          {t('message.agent-harness-kg-sub-agents-hint')}
        </Typography>
      </Box>
      <Card data-testid="knowledge-graph-sub-agents">
        {subAgents.length === 0 ? (
          <Box className={ROW_CLASS}>
            <Typography
              className="tw:text-tertiary"
              data-testid="knowledge-graph-no-sub-agents"
              size="text-sm">
              {t('message.agent-harness-kg-no-sub-agents')}
            </Typography>
          </Box>
        ) : (
          subAgents.map((agent) => (
            <Box align="center" className={ROW_CLASS} gap={4} key={agent.name}>
              <Box className="tw:flex-1" direction="col" gap={1}>
                <Typography size="text-sm" weight="semibold">
                  {agent.displayName}
                </Typography>
                <Typography className="tw:text-tertiary" size="text-sm">
                  {agent.description}
                </Typography>
              </Box>
              <Toggle
                aria-label={agent.displayName}
                data-testid={`knowledge-graph-sub-agent-${agent.name}`}
                isDisabled={!canEdit}
                isSelected={isAgentEnabled(agent.name)}
                onChange={(value) => setAgent(agent.name, value)}
              />
            </Box>
          ))
        )}
      </Card>
      <Box align="center" gap={4}>
        <Box className="tw:flex-1" direction="col" gap={1}>
          <Typography size="text-sm" weight="semibold">
            {t('label.connection-plural')}
          </Typography>
          <Typography className="tw:text-tertiary" size="text-sm">
            {t('message.agent-harness-kg-connections-hint')}
          </Typography>
        </Box>
        {renderAddConnection()}
      </Box>
      <Card data-testid="knowledge-graph-connections">
        {connectionRows.length === 0 ? (
          <Box className={ROW_CLASS}>
            <Typography
              className="tw:text-tertiary"
              data-testid="knowledge-graph-no-connections"
              size="text-sm">
              {t('message.agent-harness-kg-no-connections')}
            </Typography>
          </Box>
        ) : (
          connectionRows.map(renderConnection)
        )}
      </Card>
      {renderPendingConnections()}
    </Box>
  );
};

export default KnowledgeGraphTab;
