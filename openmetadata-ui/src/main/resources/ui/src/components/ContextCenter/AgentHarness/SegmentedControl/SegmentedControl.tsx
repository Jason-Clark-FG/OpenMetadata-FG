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
import { ButtonGroup, ButtonGroupItem } from '@openmetadata/ui-core-components';

export interface SegmentedOption<T extends string> {
  id: T;
  label: string;
  isDisabled?: boolean;
}

export interface SegmentedControlProps<T extends string> {
  'aria-label': string;
  'data-testid': string;
  options: SegmentedOption<T>[];
  value: T;
  isDisabled?: boolean;
  onChange: (value: T) => void;
}

/**
 * One choice out of a few, as a radio group: the harness's Off / Warn / Block levels, review
 * and visibility modes, and install targets. Options a harness may not take stay visible and
 * disabled, so an administrator sees the floor they inherit.
 */
const SegmentedControl = <T extends string>({
  'aria-label': ariaLabel,
  'data-testid': testId,
  options,
  value,
  isDisabled,
  onChange,
}: SegmentedControlProps<T>) => (
  <ButtonGroup
    disallowEmptySelection
    aria-label={ariaLabel}
    data-testid={testId}
    isDisabled={isDisabled}
    selectedKeys={[value]}
    size="sm"
    variant="segmented"
    onSelectionChange={(keys) => {
      const [selected] = [...keys];
      const option = options.find(({ id }) => id === selected);
      if (option) {
        onChange(option.id);
      }
    }}>
    {options.map((option) => (
      <ButtonGroupItem
        data-testid={`${testId}-${option.id}`}
        id={option.id}
        isDisabled={isDisabled || option.isDisabled}
        key={option.id}>
        {option.label}
      </ButtonGroupItem>
    ))}
  </ButtonGroup>
);

export default SegmentedControl;
