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
import { withEventDelivery } from '../../../../utils/discovery/context-center/agentHarness.utils';
import HarnessToggle from '../HarnessToggle/HarnessToggle';

export interface DeliveryTabProps {
  kind: HarnessKind;
  rows: DeliveryRow[];
  errors: HarnessBudgetErrors;
  canEdit: boolean;
  onChange: (change: (spec: AgentHarnessSpec) => AgentHarnessSpec) => void;
}

const DeliveryTab = ({
  kind,
  rows,
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
    const label = t(copy.labelKey);

    return (
      <Box
        align="start"
        className={classNames(
          'tw:border-t tw:border-secondary tw:px-5 tw:py-4 tw:first:border-t-0',
          { 'tw:opacity-55': !row.enabled }
        )}
        data-testid={`delivery-event-${row.id}`}
        gap={4}
        key={row.id}>
        <Typography
          aria-hidden
          className="tw:flex tw:size-7 tw:shrink-0 tw:items-center tw:justify-center tw:rounded-full tw:bg-brand-primary tw:text-brand-secondary"
          size="text-xs"
          weight="semibold">
          {index + 1}
        </Typography>
        <Box className="tw:min-w-0 tw:flex-1" direction="col" gap={2}>
          <Box align="center" gap={2} wrap="wrap">
            <Typography size="text-sm" weight="semibold">
              {label}
            </Typography>
            <Typography
              className="tw:font-mono tw:text-tertiary"
              size="text-xs">
              {row.id}
            </Typography>
            {row.inherited && (
              <Badge color="gray" size="sm" type="pill-color">
                {t('label.from-default')}
              </Badge>
            )}
          </Box>
          <Typography className="tw:text-tertiary" size="text-sm">
            {t(copy.descriptionKey)}
          </Typography>
          <Box gap={2} wrap="wrap">
            {copy.injectKeys.map((key) => (
              <Badge color="gray" key={key} size="sm" type="modern">
                {t(key)}
              </Badge>
            ))}
          </Box>
        </Box>
        <Box align="end" className="tw:shrink-0" direction="col" gap={1}>
          <Typography aria-hidden className="tw:text-quaternary" size="text-xs">
            {t('label.budget')}
          </Typography>
          <Box align="center" gap={2}>
            <NumberInput
              aria-label={t('label.budget-of-entity', { entity: label })}
              className="tw:w-20"
              formatOptions={{ maximumFractionDigits: 0 }}
              inputClassName="tw:text-right"
              inputDataTestId={`delivery-budget-${row.id}`}
              isDisabled={!canEdit || !row.enabled}
              isInvalid={Boolean(error)}
              showSteppers={false}
              value={row.tokenBudget}
              onChange={(value) =>
                setRow({
                  ...row,
                  tokenBudget: Number.isNaN(value) ? 0 : value,
                })
              }
            />
            <Typography
              className="tw:text-tertiary"
              data-testid={`delivery-budget-unit-${row.id}`}
              size="text-xs">
              {t('label.tokens')}
            </Typography>
          </Box>
          {error && (
            <Typography
              className="tw:max-w-50 tw:text-right tw:text-error-primary"
              data-testid={`delivery-budget-error-${row.id}`}
              size="text-xs">
              {t(BUDGET_ERROR_KEYS[error], {
                tokens: row.maxTokenBudget ?? 0,
              })}
            </Typography>
          )}
        </Box>
        <Box className="tw:shrink-0 tw:pt-6">
          <HarnessToggle
            aria-label={t('label.enable-entity', { entity: label })}
            data-testid={`delivery-enabled-${row.id}`}
            inherited={row.inherited}
            isDisabled={!canEdit}
            isSelected={row.enabled}
            onChange={(enabled) => setRow({ ...row, enabled })}
          />
        </Box>
      </Box>
    );
  };

  return (
    <Box data-testid="delivery-tab" direction="col" gap={3}>
      <Typography className="tw:text-tertiary" size="text-sm">
        {t('message.agent-harness-delivery-intro')}
      </Typography>
      <Card data-testid="delivery-events">{rows.map(renderRow)}</Card>
    </Box>
  );
};

export default DeliveryTab;
