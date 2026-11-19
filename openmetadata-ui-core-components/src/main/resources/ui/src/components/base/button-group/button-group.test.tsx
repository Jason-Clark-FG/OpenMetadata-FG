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
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { ButtonGroup, ButtonGroupItem } from './button-group';
import type { ButtonGroupVariant } from './button-group';

const renderGroup = (
  variant: ButtonGroupVariant,
  onSelectionChange = vi.fn()
) =>
  render(
    <ButtonGroup
      disallowEmptySelection
      aria-label="Level"
      defaultSelectedKeys={['warn']}
      variant={variant}
      onSelectionChange={onSelectionChange}>
      <ButtonGroupItem isDisabled id="off">
        Off
      </ButtonGroupItem>
      <ButtonGroupItem id="warn">Warn</ButtonGroupItem>
      <ButtonGroupItem id="block">Block</ButtonGroupItem>
    </ButtonGroup>
  );

describe('ButtonGroup variants', () => {
  it.each<ButtonGroupVariant>(['segmented', 'pill'])(
    'selects one option at a time and skips disabled ones (%s)',
    async (variant) => {
      const onSelectionChange = vi.fn();
      renderGroup(variant, onSelectionChange);

      await userEvent.click(screen.getByRole('radio', { name: 'Block' }));
      await userEvent.click(screen.getByRole('radio', { name: 'Off' }));

      expect(screen.getByRole('radio', { name: 'Block' })).toBeChecked();
      expect(screen.getByRole('radio', { name: 'Off' })).toBeDisabled();
      expect(onSelectionChange).toHaveBeenCalledTimes(1);
    }
  );

  it('draws the segmented track on the group and the raised pill on the selected item', () => {
    renderGroup('segmented');

    expect(screen.getByRole('radiogroup')).toHaveClass('tw:bg-secondary_alt');
    expect(screen.getByRole('radio', { name: 'Warn' })).toHaveClass(
      'tw:selected:bg-primary_alt'
    );
  });
});
