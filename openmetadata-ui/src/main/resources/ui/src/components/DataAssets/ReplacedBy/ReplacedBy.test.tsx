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
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { EntityReference } from '../../../generated/entity/type';
import { searchData } from '../../../rest/miscAPI';
import { ReplacedBy } from './ReplacedBy';

jest.mock('../../../rest/miscAPI', () => ({
  searchData: jest.fn().mockResolvedValue({
    data: { hits: { hits: [] } },
  }),
}));

jest.mock('../../../utils/ToastUtils', () => ({
  showErrorToast: jest.fn(),
}));

const replacement: EntityReference = {
  id: 'b2f1c6a4-0000-4000-8000-000000000001',
  type: 'table',
  name: 'orders_v2',
  fullyQualifiedName: 'svc.db.sales.orders_v2',
};

const renderReplacedBy = (
  props: Partial<React.ComponentProps<typeof ReplacedBy>> = {}
) => {
  const onUpdate = jest.fn().mockResolvedValue(undefined);
  render(
    <MemoryRouter>
      <ReplacedBy
        canEdit
        replacedBy={replacement}
        tableFqn="svc.db.sales.orders"
        onUpdate={onUpdate}
        {...props}
      />
    </MemoryRouter>
  );

  return { onUpdate };
};

describe('ReplacedBy', () => {
  it('links to the replacement table', () => {
    renderReplacedBy();

    expect(screen.getByTestId('replaced-by-link')).toHaveAttribute(
      'href',
      '/table/svc.db.sales.orders_v2'
    );
    expect(screen.getByTestId('replaced-by-link')).toHaveTextContent(
      'svc.db.sales.orders_v2'
    );
  });

  it('shows no edit controls to a reader without edit access', () => {
    renderReplacedBy({ canEdit: false });

    expect(screen.getByTestId('replaced-by-link')).toBeInTheDocument();
    expect(screen.queryByTestId('replaced-by-edit')).not.toBeInTheDocument();
    expect(screen.queryByTestId('replaced-by-remove')).not.toBeInTheDocument();
  });

  it('renders nothing for a reader when no replacement is recorded', () => {
    renderReplacedBy({ canEdit: false, replacedBy: undefined });

    expect(screen.queryByTestId('replaced-by')).not.toBeInTheDocument();
    expect(screen.queryByTestId('replaced-by-add')).not.toBeInTheDocument();
  });

  it('offers an editor the chance to set a replacement', async () => {
    renderReplacedBy({ replacedBy: undefined });

    fireEvent.click(screen.getByTestId('replaced-by-add'));

    expect(screen.getByTestId('replaced-by-editor')).toBeInTheDocument();

    await waitFor(() => expect(searchData).toHaveBeenCalled());
  });

  it('clears the replacement when the editor removes it', async () => {
    const { onUpdate } = renderReplacedBy();

    fireEvent.click(screen.getByTestId('replaced-by-remove'));

    await waitFor(() => expect(onUpdate).toHaveBeenCalledWith(undefined));
  });
});
