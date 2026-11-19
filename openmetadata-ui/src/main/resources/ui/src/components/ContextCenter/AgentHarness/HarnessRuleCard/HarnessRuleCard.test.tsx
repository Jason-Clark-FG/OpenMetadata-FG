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
import { fireEvent, render, screen } from '@testing-library/react';
import { EntityType } from '../../../../enums/entity.enum';
import {
  ContextRule,
  ContextSection,
} from '../../../../generated/type/personaContextDefinition';
import HarnessRuleCard from './HarnessRuleCard';

jest.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, values?: Record<string, unknown>) =>
      values ? `${key} ${JSON.stringify(values)}` : key,
  }),
}));

const ownersAdmin = JSON.stringify({
  query: {
    bool: { must: [{ term: { 'owners.displayName.keyword': 'admin' } }] },
  },
});

const renderCard = (
  rule: ContextRule,
  props: Partial<{ canEdit: boolean; inherited: boolean }> = {}
) => {
  const onEdit = jest.fn();
  const onDelete = jest.fn();
  render(
    <HarnessRuleCard
      canEdit={props.canEdit ?? true}
      inherited={props.inherited ?? false}
      rule={rule}
      onDelete={onDelete}
      onEdit={onEdit}
    />
  );

  return { onDelete, onEdit };
};

describe('HarnessRuleCard', () => {
  it('shows the filter as field, operator and value chips', () => {
    renderCard({
      entityType: EntityType.TABLE,
      name: 'Testing',
      queryFilter: ownersAdmin,
    });

    expect(screen.getByTestId('rule-query-field')).toHaveTextContent('Owners');
    expect(screen.getByTestId('rule-query-operator')).toHaveTextContent('=');
    expect(screen.getByTestId('rule-query-value')).toHaveTextContent('admin');
  });

  it('says the rule selects every entity of its type when it has no filter', () => {
    renderCard({ entityType: EntityType.TABLE, name: 'All', queryFilter: '' });

    expect(screen.getByTestId('rule-query-all')).toHaveTextContent(
      'label.all-entity'
    );
    expect(screen.queryByTestId('rule-query-field')).not.toBeInTheDocument();
  });

  it('lists sections, not "Fully rendered", for a rule stored without the flag', () => {
    renderCard({
      entityType: EntityType.GLOSSARY_TERM,
      name: 'finance-terms',
      sections: [ContextSection.Definition],
    });

    expect(screen.getByTestId('rule-sections')).toHaveTextContent(
      'label.definition'
    );
    expect(screen.queryByText('label.fully-rendered')).not.toBeInTheDocument();
  });

  it('shows "Fully rendered" in place of sections when the rule renders everything', () => {
    renderCard({
      entityType: EntityType.GLOSSARY_TERM,
      fullyRendered: true,
      name: 'finance-terms',
      sections: [ContextSection.Definition],
    });

    expect(screen.getByTestId('rule-sections')).toHaveTextContent(
      'label.fully-rendered'
    );
    expect(screen.queryByText('label.definition')).not.toBeInTheDocument();
  });

  it('caps the section chips at five and counts the rest', () => {
    renderCard({
      entityType: EntityType.TABLE,
      name: 'Wide',
      sections: [
        ContextSection.Description,
        ContextSection.Schema,
        ContextSection.Owner,
        ContextSection.Tags,
        ContextSection.Joins,
        ContextSection.Constraints,
        ContextSection.Profile,
      ],
    });

    expect(screen.getByTestId('rule-sections').children).toHaveLength(6);
    expect(
      screen.getByText('message.persona-context-more-sections {"count":2}')
    ).toBeInTheDocument();
  });

  it('puts the search-filter note in the limit slot of a search-scoped rule', () => {
    renderCard({
      entityType: EntityType.TABLE,
      filteredInSearch: true,
      maxAssets: 50,
      name: 'Scoped',
    });

    expect(screen.getByTestId('rule-limit')).toHaveTextContent(
      'label.filtered-in-search'
    );
    expect(screen.getByTestId('rule-limit')).toHaveTextContent(
      'message.persona-context-scoped-rule-summary'
    );
    expect(screen.queryByTestId('rule-sections')).not.toBeInTheDocument();
  });

  it('edits and deletes an own rule from its action menu', async () => {
    const { onEdit, onDelete } = renderCard({
      entityType: EntityType.TABLE,
      maxAssets: 200,
      name: 'Testing',
    });

    expect(screen.getByTestId('rule-limit')).toHaveTextContent(
      'message.persona-context-max-assets {"count":200}'
    );

    fireEvent.click(screen.getByTestId('context-rule-actions'));
    fireEvent.click(
      await screen.findByRole('menuitem', { name: 'label.edit' })
    );

    expect(onEdit).toHaveBeenCalledTimes(1);

    fireEvent.click(screen.getByTestId('context-rule-actions'));
    fireEvent.click(
      await screen.findByRole('menuitem', { name: 'label.delete' })
    );
    fireEvent.click(await screen.findByTestId('confirm-button'));

    expect(onDelete).toHaveBeenCalledTimes(1);
  });

  it('locks a rule inherited from the company default', () => {
    renderCard(
      { entityType: EntityType.TABLE, name: 'Certified tables' },
      { inherited: true }
    );

    expect(screen.getByTestId('inherited-context-rule-card')).toHaveTextContent(
      'label.inherited-locked'
    );
    expect(
      screen.queryByTestId('context-rule-actions')
    ).not.toBeInTheDocument();
  });
});
