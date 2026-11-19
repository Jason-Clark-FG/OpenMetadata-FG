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
  Divider,
  NumberInput,
  Toggle,
  Typography,
} from '@openmetadata/ui-core-components';
import { useTranslation } from 'react-i18next';
import {
  BUDGET_ERROR_KEYS,
  CACHE_TTL_MINUTES_RANGE,
  CHARACTER_BUDGET_RANGE,
} from '../../../../constants/discovery/agentHarness.constants';
import { AgentHarnessSpec } from '../../../../generated/api/context/agentHarnessState';
import { DraftSaveState } from '../../../../hooks/discovery/context-center/useAgentHarnessDraft';
import {
  BudgetError,
  HarnessBudgetErrors,
} from '../../../../interface/discovery/agentHarness.interface';

export interface HarnessSettingsBarProps {
  canEdit: boolean;
  enabled: boolean;
  spec: AgentHarnessSpec;
  eventTokens: number;
  errors: HarnessBudgetErrors;
  saveState: DraftSaveState;
  /** The server holds edits that are not published yet. */
  hasDraft: boolean;
  publishedVersion?: number;
  onEnabledChange: (enabled: boolean) => void;
  onChange: (change: (spec: AgentHarnessSpec) => AgentHarnessSpec) => void;
}

const SAVE_STATE_KEYS: Record<Exclude<DraftSaveState, 'saved'>, string> = {
  failed: 'message.agent-harness-save-failed',
  invalid: 'message.agent-harness-save-invalid',
  pending: 'label.unsaved-changes',
  saving: 'label.saving',
};

/** NumberField reports a cleared input as NaN; the spec stores it as absent. */
const numberOrUndefined = (value: number) =>
  Number.isNaN(value) ? undefined : value;

interface BudgetFieldProps {
  label: string;
  unit: string;
  error?: string;
  value?: number;
  widthClassName: string;
  testId: string;
  isDisabled: boolean;
  onChange: (value: number) => void;
}

const BudgetField = ({
  label,
  unit,
  error,
  value,
  widthClassName,
  testId,
  isDisabled,
  onChange,
}: BudgetFieldProps) => (
  <Box direction="col" gap={2}>
    <Typography
      aria-hidden
      className="tw:text-tertiary"
      size="text-xs"
      weight="medium">
      {label}
    </Typography>
    <Box align="center" gap={2}>
      <NumberInput
        aria-label={label}
        className={widthClassName}
        formatOptions={{ maximumFractionDigits: 0 }}
        inputDataTestId={testId}
        isDisabled={isDisabled}
        isInvalid={Boolean(error)}
        showSteppers={false}
        value={value ?? NaN}
        onChange={onChange}
      />
      <Typography
        className="tw:text-tertiary"
        data-testid={`${testId}-unit`}
        size="text-sm">
        {unit}
      </Typography>
    </Box>
    {error && (
      <Typography
        className="tw:max-w-60 tw:text-error-primary"
        data-testid={`${testId}-error`}
        size="text-xs">
        {error}
      </Typography>
    )}
  </Box>
);

const HarnessSettingsBar = ({
  canEdit,
  enabled,
  spec,
  eventTokens,
  errors,
  saveState,
  hasDraft,
  publishedVersion,
  onEnabledChange,
  onChange,
}: HarnessSettingsBarProps) => {
  const { t } = useTranslation();

  const errorText = (
    error: BudgetError | undefined,
    values: Record<string, number> = {}
  ) => (error ? t(BUDGET_ERROR_KEYS[error], values) : undefined);

  const setContext =
    (field: 'characterBudget' | 'cacheTtlMinutes') => (value: number) =>
      onChange((current) => ({
        ...current,
        context: { ...current.context, [field]: numberOrUndefined(value) },
      }));

  const statusText = () => {
    if (saveState !== 'saved') {
      return t(SAVE_STATE_KEYS[saveState]);
    }
    if (hasDraft) {
      return t('message.agent-harness-draft-unpublished');
    }

    return publishedVersion === undefined
      ? undefined
      : t('label.published-version', {
          version: `v${publishedVersion.toFixed(1)}`,
        });
  };
  const status = canEdit ? statusText() : undefined;

  return (
    <Box
      align="center"
      className="tw:rounded-xl tw:border tw:border-secondary tw:px-5 tw:py-4"
      data-testid="harness-settings-bar"
      gap={7}
      wrap="wrap">
      <Box align="center" gap={3}>
        <Typography aria-hidden size="text-sm" weight="semibold">
          {t('label.enabled')}
        </Typography>
        <Toggle
          aria-label={t('label.enabled')}
          data-testid="harness-enabled"
          isDisabled={!canEdit}
          isSelected={enabled}
          onChange={onEnabledChange}
        />
      </Box>
      <Divider className="tw:self-stretch" orientation="vertical" />
      <BudgetField
        error={errorText(errors.characterBudget, CHARACTER_BUDGET_RANGE)}
        isDisabled={!canEdit}
        label={t('label.character-budget')}
        testId="harness-character-budget"
        unit={t('label.chars')}
        value={spec.context?.characterBudget}
        widthClassName="tw:w-28"
        onChange={setContext('characterBudget')}
      />
      <BudgetField
        error={errorText(errors.perTurnTokenBudget, { tokens: eventTokens })}
        isDisabled={!canEdit}
        label={t('label.per-turn-budget')}
        testId="harness-per-turn-budget"
        unit={t('label.tokens')}
        value={spec.perTurnTokenBudget}
        widthClassName="tw:w-25"
        onChange={(value) =>
          onChange((current) => ({
            ...current,
            perTurnTokenBudget: numberOrUndefined(value),
          }))
        }
      />
      <BudgetField
        error={errorText(errors.cacheTtlMinutes, CACHE_TTL_MINUTES_RANGE)}
        isDisabled={!canEdit}
        label={t('label.cache-ttl')}
        testId="harness-cache-ttl"
        unit={t('label.minute-short')}
        value={spec.context?.cacheTtlMinutes}
        widthClassName="tw:w-16"
        onChange={setContext('cacheTtlMinutes')}
      />
      {status && (
        <Typography
          className="tw:ml-auto tw:text-tertiary"
          data-testid="harness-save-state"
          size="text-sm">
          {status}
        </Typography>
      )}
    </Box>
  );
};

export default HarnessSettingsBar;
