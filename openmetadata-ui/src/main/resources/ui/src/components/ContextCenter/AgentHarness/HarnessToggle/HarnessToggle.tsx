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
import { Toggle, Tooltip } from '@openmetadata/ui-core-components';
import { useTranslation } from 'react-i18next';

export interface HarnessToggleProps {
  'aria-label': string;
  'data-testid': string;
  isSelected: boolean;
  /** Set by the company default: shown in its real state and locked. */
  inherited: boolean;
  isDisabled?: boolean;
  onChange: (isSelected: boolean) => void;
}

/**
 * A harness switch. An inherited one is read-only rather than disabled, so a setting the
 * default turns on still reads as on, and its tooltip says where it is set.
 */
const HarnessToggle = ({
  'aria-label': ariaLabel,
  'data-testid': testId,
  isSelected,
  inherited,
  isDisabled,
  onChange,
}: HarnessToggleProps) => {
  const { t } = useTranslation();

  if (!inherited) {
    return (
      <Toggle
        aria-label={ariaLabel}
        data-testid={testId}
        isDisabled={isDisabled}
        isSelected={isSelected}
        onChange={onChange}
      />
    );
  }

  return (
    <Tooltip
      excludeTriggerFromTabOrder
      title={t('message.set-in-company-default')}
      triggerClassName="tw:inline-flex">
      <Toggle
        isReadOnly
        aria-label={ariaLabel}
        data-testid={testId}
        isSelected={isSelected}
      />
    </Tooltip>
  );
};

export default HarnessToggle;
