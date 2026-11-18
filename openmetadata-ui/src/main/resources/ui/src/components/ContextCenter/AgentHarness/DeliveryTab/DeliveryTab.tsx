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
  Card,
  NumberInput,
  Toggle,
  Typography,
} from '@openmetadata/ui-core-components';
import classNames from 'classnames';
import { useTranslation } from 'react-i18next';
import {
  BUDGET_ERROR_KEYS,
  HOOK_EVENT_COPY,
} from '../../../../constants/discovery/agentHarness.constants';
import {
  AgentHarnessSpec,
  HarnessKind,
} from '../../../../generated/api/context/agentHarnessState';
import {
  DeliveryRow,
  HarnessBudgetErrors,
} from '../../../../interface/discovery/agentHarness.interface';
import {
  enabledEventTokens,
  withEventDelivery,
} from '../../../../utils/discovery/context-center/agentHarness.utils';

export interface DeliveryTabProps {
  kind: HarnessKind;
  rows: DeliveryRow[];
  perTurnTokenBudget?: number;
  errors: HarnessBudgetErrors;
  canEdit: boolean;
  onChange: (change: (spec: AgentHarnessSpec) => AgentHarnessSpec) => void;
}

const DeliveryTab = ({
  kind,
  rows,
  perTurnTokenBudget,
  errors,
  canEdit,
  onChange,
}: DeliveryTabProps) => {
  const { t } = useTranslation();

  const setRow = (row: DeliveryRow) =>
    onChange((spec) => withEventDelivery(spec, kind, row));

  const renderRow = (row: DeliveryRow, index: number) => {
    const copy = HOOK_EVENT_COPY[row.id];
    const error = errors.events[row.id];

    return (
      <Card
        className={classNames('tw:px-4 tw:py-4', {
          'tw:opacity-55': !row.enabled,
        })}
        data-testid={`delivery-event-${row.id}`}
        key={row.id}>
        <Box align="start" gap={4} wrap="wrap">
          <Typography
            className="tw:w-6 tw:shrink-0 tw:text-quaternary"
            size="text-sm"
            weight="semibold">
            {index + 1}
          </Typography>
          <Box className="tw:min-w-60 tw:flex-1" direction="col" gap={2}>
            <Box align="center" gap={2} wrap="wrap">
              <Typography weight="semibold">{t(copy.labelKey)}</Typography>
              <Typography
                className="tw:font-mono tw:text-tertiary"
                size="text-xs">
                {row.id}
              </Typography>
              {row.inherited && (
                <Badge color="gray" size="sm">
                  {t('label.from-default')}
                </Badge>
              )}
            </Box>
            <Typography className="tw:text-tertiary" size="text-sm">
              {t(copy.descriptionKey)}
            </Typography>
            <Box align="center" gap={1} wrap="wrap">
              <Typography className="tw:text-quaternary" size="text-xs">
                {t('label.injects')}
              </Typography>
              {copy.injectKeys.map((key) => (
                <Badge color="gray" key={key} size="sm">
                  {t(key)}
                </Badge>
              ))}
            </Box>
          </Box>
          <NumberInput
            aria-label={t('label.budget-of-entity', {
              entity: t(copy.labelKey),
            })}
            className="tw:w-36"
            formatOptions={{ maximumFractionDigits: 0 }}
            hint={
              error
                ? t(BUDGET_ERROR_KEYS[error], {
                    tokens: row.maxTokenBudget ?? 0,
                  })
                : t('label.tokens')
            }
            inputDataTestId={`delivery-budget-${row.id}`}
            isDisabled={!canEdit || !row.enabled}
            isInvalid={Boolean(error)}
            label={t('label.budget')}
            showSteppers={false}
            value={row.tokenBudget}
            onChange={(value) =>
              setRow({
                ...row,
                tokenBudget: Number.isNaN(value) ? 0 : value,
              })
            }
          />
          <Box className="tw:pt-7">
            <Toggle
              aria-label={t('label.enable-entity', {
                entity: t(copy.labelKey),
              })}
              data-testid={`delivery-enabled-${row.id}`}
              isDisabled={!canEdit || row.inherited}
              isSelected={row.enabled}
              onChange={(enabled) => setRow({ ...row, enabled })}
            />
          </Box>
        </Box>
      </Card>
    );
  };

  return (
    <Box data-testid="delivery-tab" direction="col" gap={4}>
      <Typography className="tw:text-tertiary" size="text-sm">
        {t('message.agent-harness-delivery-intro')}
      </Typography>
      {rows.map(renderRow)}
      <Typography
        className={
          errors.perTurnTokenBudget
            ? 'tw:text-error-primary'
            : 'tw:text-tertiary'
        }
        data-testid="delivery-per-turn-summary"
        size="text-sm">
        {t('message.agent-harness-events-per-turn', {
          budget: perTurnTokenBudget ?? 0,
          tokens: enabledEventTokens(rows),
        })}
      </Typography>
    </Box>
  );
};

export default DeliveryTab;
