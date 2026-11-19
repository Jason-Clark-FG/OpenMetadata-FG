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
import HarnessSettingsBar, {
  HarnessSettingsBarProps,
} from './HarnessSettingsBar';

jest.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, values?: Record<string, unknown>) =>
      values ? `${key} ${JSON.stringify(values)}` : key,
  }),
}));

const renderBar = (props: Partial<HarnessSettingsBarProps> = {}) =>
  render(
    <HarnessSettingsBar
      canEdit
      enabled
      errors={{ events: {} }}
      eventTokens={13000}
      hasDraft={false}
      publishedVersion={1.2}
      saveState="saved"
      spec={{
        context: { cacheTtlMinutes: 30, characterBudget: 400000 },
        perTurnTokenBudget: 20000,
      }}
      onChange={jest.fn()}
      onEnabledChange={jest.fn()}
      {...props}
    />
  );

const follows = (first: HTMLElement, second: HTMLElement) =>
  Boolean(
    first.compareDocumentPosition(second) & Node.DOCUMENT_POSITION_FOLLOWING
  );

describe('HarnessSettingsBar', () => {
  it.each([
    ['harness-character-budget', 'label.chars'],
    ['harness-per-turn-budget', 'label.tokens'],
    ['harness-cache-ttl', 'label.minute-short'],
  ])('puts the %s unit right of its input', (testId, unitKey) => {
    renderBar();

    const unit = screen.getByTestId(`${testId}-unit`);

    expect(unit).toHaveTextContent(unitKey);
    expect(follows(screen.getByTestId(testId), unit)).toBe(true);
    expect(unit.parentElement).toContainElement(screen.getByTestId(testId));
  });

  it('labels the Enabled switch before the switch itself', () => {
    renderBar();

    expect(
      follows(
        screen.getByText('label.enabled'),
        screen.getByTestId('harness-enabled')
      )
    ).toBe(true);
  });

  it('says a draft has unpublished changes once it is saved', () => {
    renderBar({ hasDraft: true });

    expect(screen.getByTestId('harness-save-state')).toHaveTextContent(
      'message.agent-harness-draft-unpublished'
    );
  });

  it('shows the published version when there is no draft', () => {
    renderBar();

    expect(screen.getByTestId('harness-save-state')).toHaveTextContent(
      'label.published-version {"version":"v1.2"}'
    );
  });

  it('shows the save in progress over the draft state', () => {
    renderBar({ hasDraft: true, saveState: 'saving' });

    expect(screen.getByTestId('harness-save-state')).toHaveTextContent(
      'label.saving'
    );
  });
});
