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
import { fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import {
  AgentHarnessSpec,
  HarnessKind,
  MemoryReview,
  MemorySettings,
  MemoryVisibility,
} from '../../../../generated/api/context/agentHarnessState';
import MemoryTab from './MemoryTab';

const DEFAULT_MEMORY: MemorySettings = {
  capture: false,
  dedup: true,
  linkAssets: true,
  review: MemoryReview.StewardReview,
  visibility: MemoryVisibility.Persona,
};

const renderTab = (
  props: Partial<React.ComponentProps<typeof MemoryTab>> = {}
) => {
  const onChange = jest.fn();
  render(
    <MemoryRouter>
      <MemoryTab
        canEdit
        inheritedMemory={DEFAULT_MEMORY}
        kind={HarnessKind.Persona}
        spec={{}}
        onChange={onChange}
        {...props}
      />
    </MemoryRouter>
  );
  const applied = (spec: AgentHarnessSpec = {}) => {
    const [change] = onChange.mock.lastCall;

    return change(spec) as AgentHarnessSpec;
  };

  return { applied, onChange };
};

const option = (group: string, id: string) =>
  within(screen.getByTestId(group)).getByTestId(`${group}-${id}`);

describe('MemoryTab', () => {
  it('shows the default settings on a persona harness that sets none', () => {
    renderTab();

    expect(screen.getByTestId('memory-from-default')).toBeInTheDocument();
    expect(
      within(screen.getByTestId('memory-capture')).getByRole('switch')
    ).not.toBeChecked();
    expect(option('memory-visibility', 'Persona')).toHaveAttribute(
      'aria-checked',
      'true'
    );
  });

  it('holds the toggles, review and visibility in one container', () => {
    renderTab();

    const settings = screen.getByTestId('memory-settings');

    ['memory-capture', 'memory-linkAssets', 'memory-dedup'].forEach((id) =>
      expect(within(settings).getByTestId(id)).toBeInTheDocument()
    );

    expect(within(settings).getByTestId('memory-review')).toBeInTheDocument();
    expect(
      within(settings).getByTestId('memory-visibility')
    ).toBeInTheDocument();
  });

  it('copies the default settings into the persona on its first change', () => {
    const { applied } = renderTab();

    fireEvent.click(
      within(screen.getByTestId('memory-capture')).getByRole('switch')
    );

    expect(applied().memory).toEqual({ ...DEFAULT_MEMORY, capture: true });
  });

  it('offers only two review modes and forces Private for Author only', () => {
    const { applied } = renderTab({ kind: HarnessKind.Default });

    expect(
      within(screen.getByTestId('memory-review')).getAllByRole('radio')
    ).toHaveLength(2);

    fireEvent.click(option('memory-review', 'AuthorOnly'));

    expect(applied().memory).toEqual({
      ...DEFAULT_MEMORY,
      review: MemoryReview.AuthorOnly,
      visibility: MemoryVisibility.Private,
    });
  });

  it('locks visibility to Private while review is Author only', () => {
    renderTab({
      spec: {
        memory: {
          ...DEFAULT_MEMORY,
          review: MemoryReview.AuthorOnly,
          visibility: MemoryVisibility.Private,
        },
      },
    });

    expect(option('memory-visibility', 'Organization')).toBeDisabled();
    expect(option('memory-visibility', 'Persona')).toBeDisabled();
    expect(option('memory-visibility', 'Private')).toBeEnabled();
  });

  it('goes back to the default settings', () => {
    const { applied } = renderTab({
      spec: { memory: { ...DEFAULT_MEMORY, capture: true } },
    });

    fireEvent.click(screen.getByTestId('memory-use-default'));

    expect(
      applied({ memory: { ...DEFAULT_MEMORY, capture: true } }).memory
    ).toBeUndefined();
  });

  it('links to the agent memories in Memories and renders no review queue', () => {
    renderTab();

    expect(screen.getByTestId('memories-link')).toHaveAttribute(
      'href',
      '/context-center/memories?source=agent'
    );
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('is read-only without edit rights', () => {
    renderTab({ canEdit: false });

    expect(
      within(screen.getByTestId('memory-dedup')).getByRole('switch')
    ).toBeDisabled();
    expect(option('memory-review', 'StewardReview')).toBeDisabled();
    expect(screen.queryByTestId('memory-use-default')).not.toBeInTheDocument();
  });
});
