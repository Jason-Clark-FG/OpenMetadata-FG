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
  FeaturedIcon,
  Typography,
} from '@openmetadata/ui-core-components';
import { Building07 } from '@openmetadata/ui-core-components/icons';
import { useTranslation } from 'react-i18next';
import {
  DELIVERY_GROUP_LABEL_KEYS,
  HARNESS_STATUS_BADGE,
} from '../../../../constants/discovery/agentHarness.constants';
import {
  AgentHarnessState,
  GuardrailLevel,
  HarnessStatus,
  MemoryReview,
} from '../../../../generated/api/context/agentHarnessState';
import { Surface } from '../../../../generated/api/context/agentHarnessSurfaces';
import { HarnessSource } from '../../../../interface/discovery/agentHarness.interface';
import {
  deliveryGroups,
  sourceCounts,
  surfacesCellText,
} from '../../../../utils/discovery/context-center/agentHarness.utils';
import { digitFormatter } from '../../../../utils/NumberUtils';

export interface DefaultHarnessCardProps {
  state: AgentHarnessState;
  userCount?: number;
  pendingMemories?: number;
  /** Undefined while loading or when the status could not be read. */
  surfaces?: Surface[];
  onEdit: () => void;
}

interface SummaryCell {
  key: string;
  label: string;
  value: string;
  sub: string;
}

const DefaultHarnessCard = ({
  state,
  userCount,
  pendingMemories,
  surfaces,
  onEdit,
}: DefaultHarnessCardProps) => {
  const { t } = useTranslation();
  const { resolved, published, status } = state;
  const counts = sourceCounts(resolved.rules.map(({ rule }) => rule));
  const enabledEvents = resolved.events.filter(({ enabled }) => enabled);
  const groups = deliveryGroups(enabledEvents.map(({ id }) => id));
  const levels = resolved.guardrails.map(({ level }) => level);
  const blocking = levels.filter((level) => level === GuardrailLevel.Block);
  const warning = levels.filter((level) => level === GuardrailLevel.Warn);
  const badge = HARNESS_STATUS_BADGE[status];

  const cells: SummaryCell[] = [
    {
      key: 'sources',
      label: t('label.context-source-plural'),
      value: t('message.agent-harness-term-metric-count', {
        metrics: counts[HarnessSource.Metrics].pickedIds.length,
        terms: counts[HarnessSource.GlossaryTerms].pickedIds.length,
      }),
      sub: t('message.agent-harness-rule-article-count', {
        articles: counts[HarnessSource.Articles].pickedIds.length,
        rules: counts[HarnessSource.Assets].ruleCount,
      }),
    },
    {
      key: 'delivery',
      label: t('label.delivered-at'),
      value: groups.length
        ? groups.map((group) => t(DELIVERY_GROUP_LABEL_KEYS[group])).join(' · ')
        : t('label.none'),
      sub: t('message.agent-harness-event-budget-summary', {
        count: enabledEvents.length,
        tokens: digitFormatter(resolved.perTurnTokenBudget),
      }),
    },
    {
      key: 'guardrails',
      label: t('label.guardrail-plural'),
      value: t('message.agent-harness-active-count', {
        count: blocking.length + warning.length,
      }),
      sub: t('message.agent-harness-block-warn-count', {
        block: blocking.length,
        warn: warning.length,
      }),
    },
    {
      key: 'memory',
      label: t('label.agent-memory'),
      value:
        resolved.memory.review === MemoryReview.AuthorOnly
          ? t('label.author-only')
          : t('label.steward-review'),
      sub:
        pendingMemories === undefined
          ? ''
          : t('message.agent-harness-awaiting-review', {
              count: pendingMemories,
            }),
    },
    {
      key: 'surfaces',
      label: t('label.surface-plural'),
      ...surfacesCellText(surfaces, t),
    },
  ];

  return (
    <Card data-testid="default-harness-card">
      <Box
        align="center"
        className="tw:border-b tw:border-secondary tw:px-6 tw:py-5"
        gap={4}>
        <FeaturedIcon
          className="tw:shrink-0"
          color="brand"
          icon={Building07}
          size="md"
          theme="dark"
        />
        <Box className="tw:min-w-0 tw:flex-1" direction="col" gap={1}>
          <Box align="center" gap={2} wrap="wrap">
            <h2 className="tw:m-0 tw:text-lg tw:font-semibold tw:text-primary">
              {t('label.company-default-harness')}
            </h2>
            <Badge
              color={badge.color}
              data-testid="default-harness-status"
              size="sm"
              type="pill-color">
              {status === HarnessStatus.Published
                ? t('label.published-version', {
                    version: `v${published.version?.toFixed(1) ?? '0.1'}`,
                  })
                : t(badge.labelKey)}
            </Badge>
          </Box>
          <Typography className="tw:text-tertiary" size="text-sm">
            {userCount === undefined
              ? t('message.agent-harness-default-description')
              : t('message.agent-harness-default-description-with-users', {
                  count: userCount,
                })}
          </Typography>
        </Box>
        <Button
          color="secondary"
          data-testid="edit-default-harness"
          size="sm"
          onPress={onEdit}>
          {t('label.edit-default')}
        </Button>
      </Box>
      <div className="tw:grid tw:grid-cols-[repeat(auto-fit,minmax(180px,1fr))]">
        {cells.map((cell) => (
          <Box
            className="tw:border-r tw:border-secondary tw:px-6 tw:py-4 tw:last:border-r-0"
            data-testid={`default-harness-${cell.key}`}
            direction="col"
            gap={1}
            key={cell.key}>
            <Typography
              className="tw:text-quaternary"
              size="text-xs"
              weight="medium">
              {cell.label}
            </Typography>
            <Typography size="text-md" weight="semibold">
              {cell.value}
            </Typography>
            <Typography className="tw:text-tertiary" size="text-xs">
              {cell.sub}
            </Typography>
          </Box>
        ))}
      </div>
    </Card>
  );
};

export default DefaultHarnessCard;
