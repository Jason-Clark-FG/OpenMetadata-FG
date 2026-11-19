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
import { useTranslation } from 'react-i18next';
import {
  AgentHarnessSpec,
  HarnessKind,
  KnowledgeGraphSettings,
} from '../../../../generated/api/context/agentHarnessState';
import { SubAgentDefinition } from '../../../../interface/discovery/agentHarness.interface';

export interface KnowledgeGraphTabProps {
  kind: HarnessKind;
  spec: AgentHarnessSpec;
  /** The settings this harness falls back to: the default's on a persona harness. */
  inheritedKnowledgeGraph?: KnowledgeGraphSettings;
  subAgents: SubAgentDefinition[];
  canEdit: boolean;
  onChange: (change: (spec: AgentHarnessSpec) => AgentHarnessSpec) => void;
}

type KnowledgeGraphToggle = 'entry' | 'rawQueries' | 'learn';

export const KNOWLEDGE_GRAPH_DEFAULTS: Required<KnowledgeGraphSettings> = {
  entry: false,
  learn: true,
  rawQueries: true,
  subAgents: [],
};

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

const ROW_CLASS =
  'tw:border-t tw:border-secondary tw:px-4 tw:py-4 tw:first:border-t-0';

const KnowledgeGraphTab = ({
  kind,
  spec,
  inheritedKnowledgeGraph,
  subAgents,
  canEdit,
  onChange,
}: KnowledgeGraphTabProps) => {
  const { t } = useTranslation();
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
        ...change,
      },
    }));

  const isAgentEnabled = (name: string) =>
    settings.subAgents.some((agent) => agent.name === name && agent.enabled);

  const setAgent = (name: string, enabled: boolean) =>
    set({
      subAgents: [
        ...settings.subAgents.filter((agent) => agent.name !== name),
        { enabled, name },
      ],
    });

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
            <Box
              align="center"
              className={ROW_CLASS}
              gap={4}
              key={agent.name}>
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
    </Box>
  );
};

export default KnowledgeGraphTab;
