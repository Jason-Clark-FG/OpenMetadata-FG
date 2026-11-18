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
import { JsonTree, Utils as QbUtils } from '@react-awesome-query-builder/ui';
import { cloneDeep } from 'lodash';
import { SearchOutputType } from '../../../components/Explore/AdvanceSearchProvider/AdvanceSearchProvider.interface';
import {
  PERSONA_CONTEXT_DEFAULT_SECTIONS_BY_ENTITY_TYPE,
  PERSONA_CONTEXT_KNOWLEDGE_TYPES,
  PERSONA_CONTEXT_SECTIONS_BY_ENTITY_TYPE,
} from '../../../constants/discovery/contextRule.constants';
import { EntityType } from '../../../enums/entity.enum';
import { ContextRule } from '../../../generated/type/personaContextDefinition';
import { QueryFilterInterface } from '../../../pages/ExplorePage/ExplorePage.interface';
import { getTreeConfig } from '../../AdvancedSearchUtils';
import type { TreeNode } from '../../queryBuilder/url';
import { withExploreFieldKeys } from '../../queryBuilder/url';
import {
  getJsonTreeFromQueryFilter,
  isQueryFilterFullyRepresentable,
} from '../../QueryBuilderPureUtils';
import { getExplorePath } from '../../RouterUtils';
import searchClassBase from '../../SearchClassBase';

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

export const getRuleConditionSummary = (
  rule: ContextRule
): string | undefined => {
  const tree = getRuleFilterTree(rule.filterJsonTree, rule.queryFilter);
  if (!tree) {
    return undefined;
  }
  try {
    const searchIndex =
      searchClassBase.getEntityTypeSearchIndexMapping()[rule.entityType];
    const config = getTreeConfig({
      isExplorePage: false,
      searchIndex,
      searchOutputType: SearchOutputType.ElasticSearch,
    });

    return (
      QbUtils.queryString(QbUtils.loadTree(tree), config, true) || undefined
    );
  } catch {
    return undefined;
  }
};

export interface RuleConditionParts {
  field: string;
  operator: string;
  value?: string;
}

const CONDITION_SUMMARY_REGEX =
  /^(.+?)\s+(>=|<=|!=|==|=|>|<|contains|not in|in|like|starts with|ends with|is not null|is null|is not|is)\s*(.*)$/i;

export const getRuleConditionParts = (
  rule: ContextRule
): RuleConditionParts | undefined => {
  const summary = getRuleConditionSummary(rule);
  if (!summary || /\s(and|or)\s|&&|\|\|/i.test(summary)) {
    return undefined;
  }
  const match = summary.match(CONDITION_SUMMARY_REGEX);
  if (!match) {
    return undefined;
  }
  const [, field, operator, rawValue] = match;
  const value = rawValue.trim().replace(/^["']|["']$/g, '');

  return {
    field: field.trim(),
    operator: operator.trim(),
    value: value || undefined,
  };
};

export const isKnowledgeContextRule = (rule: ContextRule): boolean =>
  PERSONA_CONTEXT_KNOWLEDGE_TYPES.includes(rule.entityType as EntityType);

// The one UI mirror of the backend's PersonaContextBuilder.isFilteredInSearch.
export const isSearchScopedRule = (rule: ContextRule): boolean =>
  Boolean(rule.filteredInSearch) && !isKnowledgeContextRule(rule);
