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
  FeaturedIcon,
  ProgressBarBase,
  Table,
  Toggle,
  Typography,
} from '@openmetadata/ui-core-components';
import { User01 } from '@openmetadata/ui-core-components/icons';
import { useTranslation } from 'react-i18next';
import {
  DELIVERY_GROUP_LABEL_KEYS,
  HARNESS_STATUS_BADGE,
} from '../../../../constants/discovery/agentHarness.constants';
import {
  AgentHarnessPersonaSummary,
  HarnessAdditions,
} from '../../../../generated/api/context/agentHarnessPersonaSummary';
import { HarnessStatus } from '../../../../generated/api/context/agentHarnessState';
import {
  contextBudgetPercent,
  deliveryGroups,
} from '../../../../utils/discovery/context-center/agentHarness.utils';
import { getEntityName } from '../../../../utils/EntityNameUtils';
import { digitFormatter } from '../../../../utils/NumberUtils';

export interface PersonaHarnessTableProps {
  rows: AgentHarnessPersonaSummary[];
  canEdit: boolean;
  onOpen: (row: AgentHarnessPersonaSummary) => void;
  onEnabledChange: (row: AgentHarnessPersonaSummary, enabled: boolean) => void;
}

const ADDITION_KEYS: [keyof HarnessAdditions, string][] = [
  ['glossaryTerms', 'message.agent-harness-added-term'],
  ['metrics', 'message.agent-harness-added-metric'],
  ['articles', 'message.agent-harness-added-article'],
  ['rules', 'message.agent-harness-added-rule'],
  ['skills', 'message.agent-harness-added-skill'],
  ['guardrails', 'message.agent-harness-stricter-guardrail'],
];

const PersonaHarnessTable = ({
  rows,
  canEdit,
  onOpen,
  onEnabledChange,
}: PersonaHarnessTableProps) => {
  const { t } = useTranslation();

  const renderHarness = (row: AgentHarnessPersonaSummary) => {
    if (row.status === HarnessStatus.Inherited) {
      return (
        <Box direction="col">
          <Typography className="tw:text-tertiary" size="text-sm">
            {t('label.uses-default')}
          </Typography>
          <Typography className="tw:text-tertiary" size="text-xs">
            {t('message.agent-harness-no-persona-additions')}
          </Typography>
        </Box>
      );
    }
    const added = row.additions;
    const parts = added
      ? ADDITION_KEYS.filter(([key]) => added[key] > 0).map(([key, i18nKey]) =>
          t(i18nKey, { count: added[key] })
        )
      : [];

    return (
      <Box direction="col">
        <Typography size="text-sm" weight="medium">
          {t('label.extends-default')}
        </Typography>
        {added && (
          <Typography className="tw:text-tertiary" size="text-xs">
            {parts.length
              ? parts.join(' · ')
              : t('message.agent-harness-no-persona-additions')}
          </Typography>
        )}
      </Box>
    );
  };

  const renderBudget = (row: AgentHarnessPersonaSummary) => {
    const percent = contextBudgetPercent(row);

    return (
      <Box direction="col" gap={1}>
        <ProgressBarBase
          aria-label={t('label.context-budget-used')}
          value={percent ?? 0}
        />
        <Typography className="tw:text-tertiary" size="text-xs">
          {percent === undefined
            ? t('label.not-measured-yet')
            : t('message.agent-harness-budget-percent', {
                budget: digitFormatter(row.characterBudget),
                percent,
              })}
        </Typography>
      </Box>
    );
  };

  const renderRow = (row: AgentHarnessPersonaSummary) => {
    const persona = row.persona;
    const badge = HARNESS_STATUS_BADGE[row.status];

    return (
      <Table.Row
        className="tw:cursor-pointer"
        data-testid={`persona-harness-row-${persona.name}`}
        id={persona.id}
        key={persona.id}
        onAction={() => onOpen(row)}>
        <Table.Cell>
          <Box align="center" gap={3}>
            <FeaturedIcon
              className="tw:shrink-0"
              color="brand"
              icon={User01}
              size="md"
              theme="light"
            />
            <Box className="tw:min-w-0" direction="col">
              <Typography ellipsis weight="medium">
                {getEntityName(persona)}
              </Typography>
              <Typography ellipsis className="tw:text-tertiary" size="text-xs">
                {persona.name}
              </Typography>
            </Box>
          </Box>
        </Table.Cell>
        <Table.Cell>{row.userCount}</Table.Cell>
        <Table.Cell data-testid="persona-harness-harness">
          {renderHarness(row)}
        </Table.Cell>
        <Table.Cell>
          <Box gap={1} wrap="wrap">
            {deliveryGroups(row.enabledEvents).map((group) => (
              <Badge color="gray" key={group} size="sm">
                {t(DELIVERY_GROUP_LABEL_KEYS[group])}
              </Badge>
            ))}
          </Box>
        </Table.Cell>
        <Table.Cell data-testid="persona-harness-budget">
          {renderBudget(row)}
        </Table.Cell>
        <Table.Cell>
          <Badge
            color={badge.color}
            data-testid="persona-harness-status"
            size="sm"
            type="pill-color">
            {t(badge.labelKey)}
          </Badge>
        </Table.Cell>
        <Table.Cell>
          {/* React Aria stops a press at the switch, so toggling never opens the row. */}
          <Toggle
            aria-label={t('label.enable-entity', {
              entity: getEntityName(persona),
            })}
            data-testid={`persona-harness-enabled-${persona.name}`}
            isDisabled={!canEdit || !row.harnessId}
            isSelected={row.enabled ?? true}
            onChange={(enabled) => onEnabledChange(row, enabled)}
          />
        </Table.Cell>
      </Table.Row>
    );
  };

  return (
    <div className="tw:overflow-x-auto">
      <Table
        aria-label={t('label.persona-harness-plural')}
        className="tw:min-w-225"
        data-testid="persona-harness-table">
        <Table.Header>
          <Table.Head isRowHeader id="persona" label={t('label.persona')} />
          <Table.Head id="users" label={t('label.user-plural')} />
          <Table.Head id="harness" label={t('label.harness')} />
          <Table.Head id="delivered" label={t('label.delivered-at')} />
          <Table.Head id="budget" label={t('label.context-budget-used')} />
          <Table.Head id="status" label={t('label.status')} />
          <Table.Head id="enabled" label={t('label.enabled')} />
        </Table.Header>
        <Table.Body>{rows.map(renderRow)}</Table.Body>
      </Table>
    </div>
  );
};

export default PersonaHarnessTable;
