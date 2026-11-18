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
import { Page, Response } from '@playwright/test';
import { EntityType } from '../../../src/enums/entity.enum';
import { ContextRule } from '../../../src/generated/api/context/agentHarnessState';
import { expect, test } from '../../support/fixtures/userPages';
import { PersonaClass } from '../../support/persona/PersonaClass';
import { selectOption } from '../../utils/advancedSearch';
import {
  createPersonaHarness,
  enablePersonaRulePreloading,
  openAddRuleDrawer,
  openPersonaHarness,
  putPersonaHarnessDraft,
  saveRuleToDraft,
  waitForHarnessDraftSave,
} from '../../utils/agentHarness';
import {
  chooseSelectOption,
  getDefaultAdminAPIContext,
} from '../../utils/common';

const TWO_CONDITION_TREE = JSON.stringify({
  type: 'group',
  properties: { conjunction: 'AND' },
  children1: {
    'aaaaaaaa-0000-4000-8000-000000000001': {
      type: 'rule',
      properties: {
        field: 'tags.tagFQN',
        operator: 'select_any_in',
        value: [['PII.Sensitive']],
        valueSrc: ['value'],
      },
    },
    'bbbbbbbb-0000-4000-8000-000000000002': {
      type: 'rule',
      properties: {
        field: 'owners.name',
        operator: 'select_any_in',
        value: [['alice']],
        valueSrc: ['value'],
      },
    },
  },
});

const SEEDED_RULES: ContextRule[] = [
  {
    entityType: EntityType.TABLE,
    id: '55555555-5555-4555-8555-555555555551',
    maxAssets: 50,
    name: 'Governed datasets',
    sections: [],
  },
  {
    entityType: EntityType.TABLE,
    filterJsonTree: TWO_CONDITION_TREE,
    id: '55555555-5555-4555-8555-555555555552',
    maxAssets: 50,
    name: 'Tagged and owned',
    sections: [],
  },
];

const draftRuleNames = (response: Response) =>
  (
    response.request().postDataJSON() as {
      context?: { rules?: ContextRule[] };
    }
  ).context?.rules?.map(({ name }) => name) ?? [];

const ruleCard = (page: Page, name: string) =>
  page.getByTestId('context-rule-card').filter({ hasText: name });

const addEmptyCondition = async (page: Page) => {
  await page.getByTestId('add-context-condition').click();
  await expect(
    page.getByTestId('advanced-search-field-select').filter({ visible: true })
  ).not.toHaveCount(0);
};

/**
 * Every test edits the same persona's draft, and the draft is written whole against its revision,
 * so the tests run one at a time.
 */
test.describe.serial(
  'Agent Harness — asset rules',
  { tag: ['@Features', '@Platform'] },
  () => {
    let persona: PersonaClass;

    const openRules = (page: Page) =>
      openPersonaHarness(page, persona.responseData.fullyQualifiedName);

    test.beforeAll('Create a persona with a harness', async ({ browser }) => {
      persona = new PersonaClass();
      const { apiContext, afterAction } = await getDefaultAdminAPIContext(
        browser
      );
      await persona.create(apiContext);
      await createPersonaHarness(
        apiContext,
        persona.responseData.fullyQualifiedName
      );
      await afterAction();
    });

    test.afterAll('Remove the persona', async ({ browser }) => {
      const { apiContext, afterAction } = await getDefaultAdminAPIContext(
        browser
      );
      await persona.delete(apiContext);
      await afterAction();
    });

    test('creates, edits and deletes a rule in the draft', async ({
      adminPage: page,
    }) => {
      const ruleName = 'harness-crud-rule';
      const editedName = 'harness-crud-rule-edited';

      await openRules(page);

      await test.step('the harness starts with no rules of its own', async () => {
        await expect(page.getByTestId('context-tab')).toBeVisible();
        await expect(page.getByTestId('add-asset-rule')).toBeVisible();
        await expect(page.getByTestId('context-rule-card')).toHaveCount(0);
      });

      await test.step('the drawer shows the match preview and the Explore link', async () => {
        await openAddRuleDrawer(page);
        await page.getByTestId('context-rule-name').fill(ruleName);

        await expect(
          page.getByTestId('context-rule-match-preview')
        ).toBeVisible();
        await expect(
          page.getByRole('link', { name: 'View in Explore' })
        ).toBeVisible();
      });

      await test.step('saving writes the rule to the draft', async () => {
        const saved = await saveRuleToDraft(page);

        expect(draftRuleNames(saved)).toEqual([ruleName]);
        await expect(ruleCard(page, ruleName)).toBeVisible();
      });

      await test.step('editing renames the rule in the draft', async () => {
        await ruleCard(page, ruleName).getByTestId('edit-context-rule').click();
        await expect(page.getByTestId('form-heading')).toContainText(
          'Edit Rule'
        );
        await page.getByTestId('context-rule-name').fill(editedName);
        const saved = await saveRuleToDraft(page);

        expect(draftRuleNames(saved)).toEqual([editedName]);
        await expect(ruleCard(page, editedName)).toBeVisible();
      });

      await test.step('deleting removes it from the draft', async () => {
        await ruleCard(page, editedName)
          .getByTestId('delete-context-rule')
          .click();
        await expect(page.getByTestId('delete-modal')).toBeVisible();
        const saved = waitForHarnessDraftSave(page);
        await page.getByTestId('confirm-button').click();

        expect(draftRuleNames(await saved)).toEqual([]);
        await expect(page.getByTestId('add-asset-rule')).toBeVisible();
        await expect(page.getByTestId('context-rule-card')).toHaveCount(0);
      });
    });

    test('an unfinished condition blocks saving the rule', async ({
      adminPage: page,
    }) => {
      await openRules(page);
      await openAddRuleDrawer(page);
      await page.getByTestId('context-rule-name').fill('filter-validation');
      await addEmptyCondition(page);

      await page.getByRole('button', { name: 'Save Rule' }).click();

      await expect(page.getByTestId('context-rule-filter-error')).toContainText(
        'Finish or remove the unfinished condition before saving'
      );
      await expect(page.getByTestId('form-heading')).toBeVisible();

      await page.keyboard.press('Escape');
    });

    /**
     * Regression for #31564: a completed single-value condition on a text field was rejected as
     * unfinished. Description is a plain text input, so no async options are involved.
     */
    test('a completed Description Contains condition saves', async ({
      adminPage: page,
    }) => {
      const ruleName = 'filter-regression-31564';
      await openRules(page);
      await openAddRuleDrawer(page);
      await page.getByTestId('context-rule-name').fill(ruleName);
      await addEmptyCondition(page);

      await selectOption(
        page,
        page
          .getByTestId('advanced-search-field-select')
          .filter({ visible: true }),
        'Description',
        true
      );
      const operator = page
        .getByTestId('advanced-search-operator-select')
        .filter({ visible: true });
      await expect(operator).toBeVisible();
      await selectOption(page, operator, 'Contains', false);
      const value = page
        .getByTestId('advanced-search-value')
        .locator('input[type="text"]:not([role="combobox"])');
      await expect(value).toBeVisible();
      await value.fill('important data asset');

      const saved = await saveRuleToDraft(page);

      await expect(page.getByTestId('context-rule-filter-error')).toHaveCount(
        0
      );
      expect(draftRuleNames(saved)).toContain(ruleName);
      await expect(ruleCard(page, ruleName)).toBeVisible();
    });

    test('changing the entity type clears an unfinished condition', async ({
      adminPage: page,
    }) => {
      const ruleName = 'entity-type-switch';
      await openRules(page);
      await openAddRuleDrawer(page);
      await page.getByTestId('context-rule-name').fill(ruleName);
      await addEmptyCondition(page);
      await page.getByRole('button', { name: 'Save Rule' }).click();
      await expect(page.getByTestId('context-rule-filter-error')).toBeVisible();

      await chooseSelectOption(
        page.getByTestId('context-rule-entity-type'),
        page.getByRole('listbox').locator(`[data-key="${EntityType.TOPIC}"]`),
        page
          .getByTestId('context-rule-entity-type')
          .getByRole('button', { name: /topic/i })
      );
      const saved = await saveRuleToDraft(page);

      expect(draftRuleNames(saved)).toContain(ruleName);
    });

    test('a rule name already used on the harness is rejected', async ({
      adminPage: page,
    }) => {
      const ruleName = 'duplicate-name';
      await openRules(page);

      await openAddRuleDrawer(page);
      await page.getByTestId('context-rule-name').fill(ruleName);
      await saveRuleToDraft(page);
      await expect(ruleCard(page, ruleName)).toBeVisible();

      await openAddRuleDrawer(page);
      await page.getByTestId('context-rule-name').fill(ruleName);
      await page.getByRole('button', { name: 'Save Rule' }).click();

      await expect(page.getByText('Name already exists')).toBeVisible();
      await page.keyboard.press('Escape');
    });

    test('max assets clamps to 1000 for a preloaded rule', async ({
      adminPage: page,
    }) => {
      await openRules(page);
      await openAddRuleDrawer(page);
      const maxAssets = page.getByTestId('context-rule-max-assets');

      await enablePersonaRulePreloading(page);
      await expect(maxAssets).toBeEnabled();
      await maxAssets.fill('9999');
      await expect(maxAssets).toHaveValue('9999');
      await maxAssets.blur();

      await expect(maxAssets).toHaveValue('1000');
      await page.keyboard.press('Escape');
    });

    test('Always in context and Fully rendered can be switched on and off', async ({
      adminPage: page,
    }) => {
      await openRules(page);
      await openAddRuleDrawer(page);
      await enablePersonaRulePreloading(page);

      for (const testId of [
        'context-rule-always-in-context',
        'context-rule-fully-rendered',
      ]) {
        const toggle = page.getByTestId(testId).getByRole('switch');
        await expect(toggle).toBeEnabled();
        await toggle.press('Space');
        await expect(toggle).toBeChecked();
        await toggle.press('Space');
        await expect(toggle).not.toBeChecked();
      }

      await page.keyboard.press('Escape');
    });

    test('knowledge entity type forces Fully rendered on and disables it', async ({
      adminPage: page,
    }) => {
      await openRules(page);
      await openAddRuleDrawer(page);

      const glossaryTermSelected = page
        .getByTestId('context-rule-entity-type')
        .getByRole('button', { name: /Glossary Term/ });
      await chooseSelectOption(
        page.getByTestId('context-rule-entity-type'),
        page
          .getByRole('listbox')
          .locator(`[data-key="${EntityType.GLOSSARY_TERM}"]`),
        glossaryTermSelected
      );
      await expect(glossaryTermSelected).toBeVisible();

      const fullyRendered = page
        .getByTestId('context-rule-fully-rendered')
        .getByRole('switch');
      await expect(fullyRendered).toBeChecked();
      await expect(fullyRendered).toBeDisabled();

      await page.keyboard.press('Escape');
    });

    test('rule cards summarize a rule with no filter and one with two conditions', async ({
      adminPage: page,
      browser,
    }) => {
      const { apiContext, afterAction } = await getDefaultAdminAPIContext(
        browser
      );
      await putPersonaHarnessDraft(
        apiContext,
        persona.responseData.fullyQualifiedName,
        { context: { rules: SEEDED_RULES } }
      );
      await afterAction();

      await openRules(page);

      await expect(
        ruleCard(page, 'Governed datasets').getByText('All Tables', {
          exact: true,
        })
      ).toBeVisible();
      await expect(
        ruleCard(page, 'Tagged and owned').getByText('2 conditions', {
          exact: true,
        })
      ).toBeVisible();
    });
  }
);
