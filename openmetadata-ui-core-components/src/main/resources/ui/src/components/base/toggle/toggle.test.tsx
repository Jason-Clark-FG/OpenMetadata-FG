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
import { Toggle } from './toggle';

describe('Toggle', () => {
  it('keeps a read-only toggle on, with the on colour, when it is clicked', async () => {
    const onChange = vi.fn();
    render(<Toggle isReadOnly isSelected label="Locked" onChange={onChange} />);

    const toggle = screen.getByRole('switch', { name: 'Locked' });
    await userEvent.click(toggle);

    expect(toggle).toBeChecked();
    expect(toggle).toHaveAttribute('aria-readonly', 'true');
    expect(onChange).not.toHaveBeenCalled();
    expect(screen.getByTestId('toggle-root')).toHaveClass('tw:bg-brand-solid');
  });
});
