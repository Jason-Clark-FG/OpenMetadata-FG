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
import { PERSONA_CONTEXT_ASSET_TYPES } from '../../../constants/discovery/contextRule.constants';
import { EntityType } from '../../../enums/entity.enum';
import { ContextSection } from '../../../generated/type/personaContextDefinition';
import {
  getDefaultPersonaContextSections,
  getPersonaContextSections,
  getRuleConditionCount,
  getRuleExplorePath,
  getRuleFilterTree,
  getRuleQuery,
  isKnowledgeContextRule,
  isRuleFilterFullyRendered,
  isSearchScopedRule,
  parseRuleFilterTree,
} from './contextRule.utils';

describe('contextRule.utils', () => {
  it('returns entity-specific section registries and defaults', () => {
    expect(PERSONA_CONTEXT_ASSET_TYPES).toEqual([
      EntityType.TABLE,
      EntityType.TOPIC,
      EntityType.DASHBOARD,
      EntityType.CHART,
      EntityType.DASHBOARD_DATA_MODEL,
      EntityType.PIPELINE,
      EntityType.MLMODEL,
      EntityType.CONTAINER,
      EntityType.DATABASE,
      EntityType.DATABASE_SCHEMA,
      EntityType.STORED_PROCEDURE,
      EntityType.SEARCH_INDEX,
      EntityType.API_COLLECTION,
      EntityType.API_ENDPOINT,
      EntityType.DATA_PRODUCT,
    ]);
    expect(getPersonaContextSections(EntityType.METRIC)).toContain(
      ContextSection.FormulaExpression
    );
    expect(getPersonaContextSections(EntityType.KNOWLEDGE_PAGE)).toContain(
      ContextSection.FullBody
    );
    expect(getDefaultPersonaContextSections(EntityType.GLOSSARY_TERM)).toEqual([
      ContextSection.Definition,
    ]);
    expect(getPersonaContextSections(EntityType.TABLE)).toContain(
      ContextSection.DataQuality
    );
    expect(getPersonaContextSections(EntityType.DATA_PRODUCT)).toContain(
      ContextSection.Lineage
    );
    expect(getDefaultPersonaContextSections(EntityType.TABLE)).toContain(
      ContextSection.Joins
    );
    expect(getDefaultPersonaContextSections(EntityType.TABLE)).toContain(
      ContextSection.Metrics
    );
  });

  it('parses stored query trees and counts nested rules', () => {
    const tree = JSON.stringify({
      children1: [
        { id: 'one', type: 'rule' },
        {
          children1: [{ id: 'two', type: 'rule' }],
          id: 'group',
          type: 'group',
        },
      ],
      id: 'root',
      type: 'group',
    });

    expect(parseRuleFilterTree(tree)).toBeDefined();
    expect(getRuleConditionCount(tree)).toBe(2);
    expect(parseRuleFilterTree('{invalid')).toBeUndefined();
  });

  it('reconstructs a query tree when only the Elasticsearch filter was stored', () => {
    const tree = getRuleFilterTree(
      undefined,
      JSON.stringify({
        query: {
          bool: {
            must: [
              {
                bool: {
                  must: [{ term: { entityType: EntityType.TABLE } }],
                },
              },
            ],
          },
        },
      })
    );

    expect(tree).toBeDefined();
    expect(getRuleConditionCount(JSON.stringify(tree))).toBe(1);
  });

  // The backend stores `queryFilter` as a free-form JSON object and embeds the
  // DSL `query` node verbatim — it never requires `bool.must`. Rules created via
  // the REST API (which never populates `filterJsonTree`) use `should`, `filter`,
  // `must_not`, or bare `term` shapes. Each of these must reconstruct to a
  // non-empty tree so the editor shows the saved conditions and the rule card
  // shows a non-zero condition count.
  it.each([
    [
      'bool.should (root OR)',
      {
        query: {
          bool: {
            should: [{ term: { deleted: false } }, { term: { name: 'x' } }],
          },
        },
      },
      2,
    ],
    [
      'bool.filter',
      { query: { bool: { filter: [{ term: { deleted: false } }] } } },
      1,
    ],
    [
      'bool.must_not',
      { query: { bool: { must_not: [{ term: { deleted: true } }] } } },
      1,
    ],
    [
      'bare query.term',
      { query: { term: { fullyQualifiedName: 'dim_customer' } } },
      1,
    ],
    [
      'bare term without query wrapper',
      { term: { 'tier.tagFQN': 'Tier.Tier1' } },
      1,
    ],
    [
      'single must clause',
      { query: { bool: { must: [{ term: { name: 'x' } }] } } },
      1,
    ],
  ])(
    'reconstructs a %s shape from queryFilter alone',
    (_label, filter, expectedCount) => {
      const tree = getRuleFilterTree(undefined, JSON.stringify(filter));

      expect(tree).toBeDefined();
      expect(getRuleConditionCount(JSON.stringify(tree))).toBe(expectedCount);
    }
  );

  it('returns undefined when the queryFilter is empty', () => {
    expect(getRuleFilterTree(undefined, '')).toBeUndefined();
    expect(getRuleFilterTree(undefined, '{}')).toBeUndefined();
  });

  it('returns undefined when the queryFilter is not valid JSON', () => {
    expect(getRuleFilterTree(undefined, '{invalid')).toBeUndefined();
  });

  it('treats a filter as fully rendered only when no condition is dropped', () => {
    const tier = { term: { 'tier.tagFQN': 'Tier.Tier1' } };
    const pii = { term: { 'tags.tagFQN': 'PII.Sensitive' } };
    const mixed = JSON.stringify({
      query: { bool: { should: [tier], filter: [pii] } },
    });

    expect(isRuleFilterFullyRendered(undefined, undefined)).toBe(true);
    expect(
      isRuleFilterFullyRendered(
        undefined,
        JSON.stringify({ query: { bool: { filter: [tier, pii] } } })
      )
    ).toBe(true);
    expect(isRuleFilterFullyRendered(undefined, mixed)).toBe(false);
    expect(isRuleFilterFullyRendered(undefined, '{invalid')).toBe(false);
    // A persisted tree is exact, whatever the DSL looks like.
    expect(
      isRuleFilterFullyRendered(JSON.stringify({ type: 'group' }), mixed)
    ).toBe(true);
  });

  it('identifies knowledge rules', () => {
    expect(
      isKnowledgeContextRule({
        entityType: EntityType.GLOSSARY_TERM,
        name: 'Terms',
      })
    ).toBe(true);
    expect(
      isKnowledgeContextRule({ entityType: EntityType.TABLE, name: 'Tables' })
    ).toBe(false);
  });

  it('does not treat a knowledge rule as search-scoped even when the flag is set', () => {
    // The server ignores filteredInSearch on knowledge types, and the API accepts an explicit true
    // there, so trusting the flag would claim a rule narrows search while the server preloads it.
    expect(
      isSearchScopedRule({
        entityType: EntityType.GLOSSARY_TERM,
        filteredInSearch: true,
        name: 'Terms',
      })
    ).toBe(false);
    expect(
      isSearchScopedRule({
        entityType: EntityType.TABLE,
        filteredInSearch: true,
        name: 'Tables',
      })
    ).toBe(true);
  });
});

// Explore validates a deep-linked tree against its own config and silently
// resets when a field is unknown. This editor is pinned to one entity type, so
// its custom-property keys omit the entity segment Explore expects — without
// the rewrite the link landed on the unfiltered estate with no filter chip.
describe('getRuleExplorePath', () => {
  it('scopes a no-text persona link with an explicit entity-type quick filter', () => {
    const url = new URL(
      getRuleExplorePath(EntityType.TABLE),
      'http://localhost'
    );

    expect(url.pathname).toBe('/explore/');
    expect(url.searchParams.get('search')).toBe('');
    expect(JSON.parse(url.searchParams.get('quickFilter') ?? '{}')).toEqual({
      query: {
        bool: {
          must: [
            { bool: { should: [{ term: { entityType: EntityType.TABLE } }] } },
          ],
        },
      },
    });
  });

  const filterJsonTree = JSON.stringify({
    id: 'root',
    type: 'group',
    properties: { conjunction: 'AND', not: false },
    children1: {
      r1: {
        type: 'rule',
        id: 'r1',
        properties: {
          field: 'extension.testCp.keyword',
          operator: 'equal',
          value: ['2026-09-03'],
          valueSrc: ['value'],
        },
      },
    },
  });

  const fieldsIn = (path: string) =>
    decodeURIComponent(path).match(/"field":"[^"]+"/g) ?? [];

  it('should deep link with the entity-segmented custom property key', () => {
    const path = getRuleExplorePath(EntityType.TABLE, filterJsonTree);

    expect(fieldsIn(path)).toContain(
      '"field":"extension.table.testCp.keyword"'
    );
  });

  it('should not rewrite a key that already carries the segment', () => {
    const already = filterJsonTree.replace(
      'extension.testCp.keyword',
      'extension.table.testCp.keyword'
    );

    expect(fieldsIn(getRuleExplorePath(EntityType.TABLE, already))).toContain(
      '"field":"extension.table.testCp.keyword"'
    );
  });
});

describe('getRuleQuery', () => {
  const ownersAdmin = { term: { 'owners.displayName.keyword': 'admin' } };
  const tier1 = { term: { 'tier.tagFQN': 'Tier.Tier1' } };
  const queryOf = (query: unknown) =>
    getRuleQuery({ queryFilter: JSON.stringify({ query }) });

  it('reads one condition as a field, an operator and a value', () => {
    expect(queryOf({ bool: { must: [ownersAdmin] } })).toEqual({
      conditions: [{ field: 'Owners', operator: '=', value: 'admin' }],
      conjunction: 'AND',
      kind: 'conditions',
    });
  });

  it('keeps every condition of a flat AND or OR filter', () => {
    expect(queryOf({ bool: { should: [ownersAdmin, tier1] } })).toEqual({
      conditions: [
        { field: 'Owners', operator: '=', value: 'admin' },
        { field: 'Tier', operator: '=', value: 'Tier.Tier1' },
      ],
      conjunction: 'OR',
      kind: 'conditions',
    });
  });

  it('turns an excluded value into a not-equal condition', () => {
    expect(queryOf({ bool: { must_not: [tier1] } })).toEqual({
      conditions: [{ field: 'Tier', operator: '!=', value: 'Tier.Tier1' }],
      conjunction: 'AND',
      kind: 'conditions',
    });
  });

  const rule = (field: string, operator: string, value: unknown) => ({
    properties: { field, operator, value: [value] },
    type: 'rule',
  });

  it('reads a stored any-of condition with all its values', () => {
    const filterJsonTree = JSON.stringify({
      children1: {
        a: rule('owners.displayName.keyword', 'select_any_in', [
          'admin',
          'bob',
        ]),
      },
      properties: { conjunction: 'AND' },
      type: 'group',
    });

    expect(getRuleQuery({ filterJsonTree })).toEqual({
      conditions: [{ field: 'Owners', operator: 'in', value: 'admin, bob' }],
      conjunction: 'AND',
      kind: 'conditions',
    });
  });

  it('counts the conditions of a stored filter that mixes AND and OR', () => {
    const filterJsonTree = JSON.stringify({
      children1: {
        a: {
          children1: {
            b: rule('owners.displayName.keyword', 'select_equals', 'admin'),
            c: rule('tier.tagFQN', 'select_equals', 'Tier.Tier1'),
          },
          properties: { conjunction: 'OR' },
          type: 'group',
        },
        d: rule('service.displayName.keyword', 'select_equals', 'mysql'),
      },
      properties: { conjunction: 'AND' },
      type: 'group',
    });

    expect(getRuleQuery({ filterJsonTree })).toEqual({
      count: 3,
      kind: 'count',
    });
  });

  it('selects every entity when the rule has no filter', () => {
    expect(getRuleQuery({ queryFilter: '' })).toEqual({ kind: 'all' });
    expect(getRuleQuery({})).toEqual({ kind: 'all' });
  });
});
