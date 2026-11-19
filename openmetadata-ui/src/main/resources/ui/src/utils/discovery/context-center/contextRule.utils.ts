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
import { JsonTree } from '@react-awesome-query-builder/ui';
import { cloneDeep } from 'lodash';
import {
  PERSONA_CONTEXT_DEFAULT_SECTIONS_BY_ENTITY_TYPE,
  PERSONA_CONTEXT_KNOWLEDGE_TYPES,
  PERSONA_CONTEXT_SECTIONS_BY_ENTITY_TYPE,
} from '../../../constants/discovery/contextRule.constants';
import { EntityType } from '../../../enums/entity.enum';
import { ContextRule } from '../../../generated/type/personaContextDefinition';
import { QueryFilterInterface } from '../../../pages/ExplorePage/ExplorePage.interface';
import type { TreeNode } from '../../queryBuilder/url';
import { withExploreFieldKeys } from '../../queryBuilder/url';
import {
  getJsonTreeFromQueryFilter,
  isQueryFilterFullyRepresentable,
} from '../../QueryBuilderPureUtils';
import { getExplorePath } from '../../RouterUtils';

export const getPersonaContextSections = (entityType: string) =>
  PERSONA_CONTEXT_SECTIONS_BY_ENTITY_TYPE[entityType] ?? [];

export const getDefaultPersonaContextSections = (entityType: string) =>
  cloneDeep(PERSONA_CONTEXT_DEFAULT_SECTIONS_BY_ENTITY_TYPE[entityType] ?? []);

export const parseRuleFilterTree = (
  filterJsonTree?: string
): JsonTree | undefined => {
  if (!filterJsonTree) {
    return undefined;
  }
  try {
    return JSON.parse(filterJsonTree) as JsonTree;
  } catch {
    return undefined;
  }
};

export const getRuleFilterTree = (
  filterJsonTree?: string,
  queryFilter?: string
): JsonTree | undefined => {
  const persistedTree = parseRuleFilterTree(filterJsonTree);
  if (persistedTree || !queryFilter) {
    return persistedTree;
  }
  try {
    const parsedFilter: unknown = JSON.parse(queryFilter);
    if (
      !parsedFilter ||
      typeof parsedFilter !== 'object' ||
      Array.isArray(parsedFilter)
    ) {
      return undefined;
    }

    const reconstructed = getJsonTreeFromQueryFilter(
      parsedFilter as QueryFilterInterface
    );

    return Object.keys(reconstructed).length
      ? (reconstructed as JsonTree)
      : undefined;
  } catch {
    return undefined;
  }
};

// False when the editor can show only part of the saved filter (or none of it): a
// persisted tree is exact, a reconstructed one may have dropped conditions.
export const isRuleFilterFullyRendered = (
  filterJsonTree?: string,
  queryFilter?: string
): boolean => {
  if (!queryFilter || parseRuleFilterTree(filterJsonTree)) {
    return true;
  }
  try {
    const parsedFilter: unknown = JSON.parse(queryFilter);

    return (
      Boolean(parsedFilter) &&
      typeof parsedFilter === 'object' &&
      !Array.isArray(parsedFilter) &&
      isQueryFilterFullyRepresentable(parsedFilter as QueryFilterInterface)
    );
  } catch {
    return false;
  }
};

export const getRuleExplorePath = (
  entityType: string,
  filterJsonTree?: string,
  queryFilter?: string
): string => {
  const tree = getRuleFilterTree(filterJsonTree, queryFilter);
  // Explore validates the deep-linked tree against its own config and silently resets when a field is unknown.
  const exploreTree = tree
    ? withExploreFieldKeys(tree as unknown as TreeNode, entityType)
    : tree;

  return getExplorePath({
    extraParameters: {
      quickFilter: JSON.stringify({
        query: {
          bool: {
            must: [{ bool: { should: [{ term: { entityType } }] } }],
          },
        },
      }),
      ...(exploreTree ? { queryFilter: JSON.stringify(exploreTree) } : {}),
    },
    isPersistFilters: false,
    search: '',
  });
};

export const getRuleConditionCount = (
  filterJsonTree?: string,
  queryFilter?: string
): number => {
  const tree = getRuleFilterTree(filterJsonTree, queryFilter);
  if (!tree) {
    return 0;
  }

  const countRules = (node: unknown): number => {
    if (!node || typeof node !== 'object' || Array.isArray(node)) {
      return 0;
    }

    const treeNode = node as Record<string, unknown>;
    if (treeNode.type === 'rule') {
      return 1;
    }
    const children = treeNode.children1;
    if (!children || typeof children !== 'object') {
      return 0;
    }

    return Object.values(children).reduce(
      (total, child) => total + countRules(child),
      0
    );
  };

  return countRules(tree);
};

export interface RuleCondition {
  field: string;
  operator: string;
  value?: string;
}

/** What a rule's filter selects, shaped for the `field op value` chips on a rule card. */
export type RuleQuery =
  | { kind: 'all' }
  | { kind: 'conditions'; conjunction: string; conditions: RuleCondition[] }
  | { kind: 'count'; count: number };

const OPERATOR_SYMBOLS: Record<string, string> = {
  between: 'between',
  equal: '=',
  greater: '>',
  greater_or_equal: '>=',
  is_not_null: 'is not null',
  is_null: 'is null',
  less: '<',
  less_or_equal: '<=',
  like: 'contains',
  not_equal: '!=',
  not_like: 'not contains',
  regexp: 'matches',
  select_any_in: 'in',
  select_equals: '=',
  select_not_any_in: 'not in',
  select_not_equals: '!=',
};

const NEGATED_OPERATORS: Record<string, string> = {
  equal: 'not_equal',
  is_not_null: 'is_null',
  is_null: 'is_not_null',
  like: 'not_like',
  not_equal: 'equal',
  not_like: 'like',
  select_any_in: 'select_not_any_in',
  select_equals: 'select_not_equals',
  select_not_any_in: 'select_any_in',
  select_not_equals: 'select_equals',
};

const humanize = (key: string) =>
  key
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/[_-]+/g, ' ')
    .replace(/^./, (first) => first.toUpperCase());

// Search field keys name an index path (`owners.displayName.keyword`); the card shows the
// attribute (`Owners`), or the property name for a custom property (`extension.<type>.<name>`).
const fieldLabel = (field: string) => {
  const path = field.split('.').filter((part) => part !== 'keyword');

  return humanize(path[0] === 'extension' ? path[path.length - 1] : path[0]);
};

const valueText = (value: unknown): string | undefined => {
  if (value === undefined || value === null || value === '') {
    return undefined;
  }
  if (Array.isArray(value)) {
    const parts = value.map(valueText).filter(Boolean);

    return parts.length ? parts.join(', ') : undefined;
  }

  return String(value);
};

interface FilterNode {
  type?: string;
  properties?: {
    conjunction?: string;
    not?: boolean;
    field?: string;
    operator?: string;
    value?: unknown[];
  };
  children1?: Record<string, FilterNode> | FilterNode[];
}

interface FlatConditions {
  conjunction?: string;
  conditions: RuleCondition[];
}

const childrenOf = (node: FilterNode): FilterNode[] =>
  node.children1 ? Object.values(node.children1) : [];

const toCondition = (
  node: FilterNode,
  negated: boolean
): RuleCondition | undefined => {
  const { field, operator, value } = node.properties ?? {};
  if (!field || !operator) {
    return undefined;
  }
  const effective = negated ? NEGATED_OPERATORS[operator] : operator;
  if (!effective) {
    return undefined;
  }

  return {
    field: fieldLabel(field),
    operator: OPERATOR_SYMBOLS[effective] ?? effective.replace(/_/g, ' '),
    value: valueText(value),
  };
};

/**
 * Flattens the filter into one conjunction of conditions. A group with a single child only
 * wraps it, and a negated single condition takes the opposite operator. Anything richer
 * (mixed AND / OR, a negated group) returns undefined and is shown as a condition count.
 */
const flattenConditions = (
  node: FilterNode,
  negated = false
): FlatConditions | undefined => {
  if (node.type === 'rule') {
    const condition = toCondition(node, negated);

    return condition ? { conditions: [condition] } : undefined;
  }
  const children = childrenOf(node);
  const isNegated = negated !== Boolean(node.properties?.not);
  if (children.length === 1) {
    return flattenConditions(children[0], isNegated);
  }
  if (isNegated) {
    return undefined;
  }
  const conjunction = (node.properties?.conjunction ?? 'AND').toUpperCase();
  const conditions: RuleCondition[] = [];
  for (const child of children) {
    const flat = flattenConditions(child);
    if (
      !flat ||
      (flat.conditions.length > 1 && flat.conjunction !== conjunction)
    ) {
      return undefined;
    }
    conditions.push(...flat.conditions);
  }

  return { conditions, conjunction };
};

export const getRuleQuery = (
  rule: Pick<ContextRule, 'filterJsonTree' | 'queryFilter'>
): RuleQuery => {
  const tree = getRuleFilterTree(rule.filterJsonTree, rule.queryFilter);
  const count = getRuleConditionCount(rule.filterJsonTree, rule.queryFilter);
  if (!tree || count === 0) {
    return { kind: 'all' };
  }
  const flat = flattenConditions(tree as FilterNode);

  return flat?.conditions.length
    ? {
        conditions: flat.conditions,
        conjunction: flat.conjunction ?? 'AND',
        kind: 'conditions',
      }
    : { count, kind: 'count' };
};

export const ruleConditionText = ({ field, operator, value }: RuleCondition) =>
  [field, operator, value].filter(Boolean).join(' ');

export const isKnowledgeContextRule = (rule: ContextRule): boolean =>
  PERSONA_CONTEXT_KNOWLEDGE_TYPES.includes(rule.entityType as EntityType);

// The one UI mirror of the backend's PersonaContextBuilder.isFilteredInSearch.
export const isSearchScopedRule = (rule: ContextRule): boolean =>
  Boolean(rule.filteredInSearch) && !isKnowledgeContextRule(rule);
