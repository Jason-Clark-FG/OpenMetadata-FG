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
  onEnabledChange: (enabled: boolean) => void;
  onChange: (change: (spec: AgentHarnessSpec) => AgentHarnessSpec) => void;
}

const SAVE_STATE_KEYS: Record<DraftSaveState, string> = {
  failed: 'message.agent-harness-save-failed',
  invalid: 'message.agent-harness-save-invalid',
  pending: 'label.unsaved-changes',
  saved: 'label.draft-saved',
  saving: 'label.saving',
};

/** NumberField reports a cleared input as NaN; the spec stores it as absent. */
const numberOrUndefined = (value: number) =>
  Number.isNaN(value) ? undefined : value;

const HarnessSettingsBar = ({
  canEdit,
  enabled,
  spec,
  eventTokens,
  errors,
  saveState,
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

  return (
    <Box
      align="start"
      className="tw:rounded-lg tw:border tw:border-secondary tw:px-4 tw:py-3"
      data-testid="harness-settings-bar"
      gap={6}
      wrap="wrap">
      <Box className="tw:pt-7">
        <Toggle
          data-testid="harness-enabled"
          isDisabled={!canEdit}
          isSelected={enabled}
          label={t('label.enabled')}
          size="sm"
          onChange={onEnabledChange}
        />
      </Box>
      <NumberInput
        className="tw:w-44"
        formatOptions={{ maximumFractionDigits: 0 }}
        hint={
          errorText(errors.characterBudget, CHARACTER_BUDGET_RANGE) ??
          t('label.chars')
        }
        inputDataTestId="harness-character-budget"
        isDisabled={!canEdit}
        isInvalid={Boolean(errors.characterBudget)}
        label={t('label.character-budget')}
        showSteppers={false}
        value={spec.context?.characterBudget ?? NaN}
        onChange={setContext('characterBudget')}
      />
      <NumberInput
        className="tw:w-44"
        formatOptions={{ maximumFractionDigits: 0 }}
        hint={
          errorText(errors.perTurnTokenBudget, { tokens: eventTokens }) ??
          t('label.tokens')
        }
        inputDataTestId="harness-per-turn-budget"
        isDisabled={!canEdit}
        isInvalid={Boolean(errors.perTurnTokenBudget)}
        label={t('label.per-turn-budget')}
        showSteppers={false}
        value={spec.perTurnTokenBudget ?? NaN}
        onChange={(value) =>
          onChange((current) => ({
            ...current,
            perTurnTokenBudget: numberOrUndefined(value),
          }))
        }
      />
      <NumberInput
        className="tw:w-36"
        formatOptions={{ maximumFractionDigits: 0 }}
        hint={
          errorText(errors.cacheTtlMinutes, CACHE_TTL_MINUTES_RANGE) ??
          t('label.minute-plural')
        }
        inputDataTestId="harness-cache-ttl"
        isDisabled={!canEdit}
        isInvalid={Boolean(errors.cacheTtlMinutes)}
        label={t('label.cache-ttl')}
        showSteppers={false}
        value={spec.context?.cacheTtlMinutes ?? NaN}
        onChange={setContext('cacheTtlMinutes')}
      />
      {canEdit && (
        <Typography
          className="tw:ml-auto tw:self-center tw:text-tertiary"
          data-testid="harness-save-state"
          size="text-sm">
          {t(SAVE_STATE_KEYS[saveState])}
        </Typography>
      )}
    </Box>
  );
};

export default HarnessSettingsBar;
