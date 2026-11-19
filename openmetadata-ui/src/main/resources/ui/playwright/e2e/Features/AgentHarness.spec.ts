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
import { readFile } from 'fs/promises';
import { HarnessStatus } from '../../../src/generated/api/context/agentHarnessState';
import { expect, test } from '../../support/fixtures/userPages';
import { PersonaClass } from '../../support/persona/PersonaClass';
import {
  AGENT_HARNESS_LIST_ROUTE,
  getPersonaHarness,
  openAddRuleDrawer,
  saveRuleToDraft,
  waitForHarnessPublish,
  zipEntryNames,
} from '../../utils/agentHarness';
import { getDefaultAdminAPIContext } from '../../utils/common';
import { waitForAllLoadersToDisappear } from '../../utils/entity';
import { waitForResponseWithStatus } from '../../utils/waitHelpers';

const PLUGIN_ENTRIES = [
  '.claude-plugin/plugin.json',
  '.codex-plugin/plugin.json',
  '.mcp.json',
  'hooks/hooks.json',
];

test.describe(
  'Agent Harness — persona harness lifecycle',
  { tag: ['@Features', '@Platform'] },
  () => {
    let persona: PersonaClass;

    test.beforeAll(
      'Create a persona without a harness',
      async ({ browser }) => {
        persona = new PersonaClass();
        const { apiContext, afterAction } = await getDefaultAdminAPIContext(
          browser
        );
        await persona.create(apiContext);
        await afterAction();
      }
    );

    test.afterAll('Remove the persona', async ({ browser }) => {
      const { apiContext, afterAction } = await getDefaultAdminAPIContext(
        browser
      );
      await persona.delete(apiContext);
      await afterAction();
    });

    test('creates a persona harness, adds a rule, publishes it and downloads the Claude plugin', async ({
      adminPage: page,
      browser,
    }) => {
      test.slow();
      const ruleName = `pw-harness-rule-${persona.id}`;

      await test.step('create the harness from the list page', async () => {
        await page.goto(AGENT_HARNESS_LIST_ROUTE, {
          waitUntil: 'domcontentloaded',
        });
        await waitForAllLoadersToDisappear(page);
        await page.getByTestId('new-persona-harness').click();

        const modal = page.getByTestId('new-persona-harness-modal');
        await expect(modal).toBeVisible();
        await modal
          .getByText(persona.data.displayName, { exact: true })
          .click();
        await expect(
          modal.getByRole('radio', { name: persona.data.displayName })
        ).toBeChecked();

        const created = waitForResponseWithStatus(
          page,
          (response) =>
            response.request().method() === 'POST' &&
            new URL(response.url()).pathname ===
              `/api/v1/agentHarness/persona/${encodeURIComponent(
                persona.responseData.fullyQualifiedName
              )}`,
          201
        );
        await modal.getByTestId('create-persona-harness').click();
        await created;

        await expect(
          page.getByTestId('agent-harness-editor-page')
        ).toBeVisible();
        await expect(page.getByTestId('context-tab')).toBeVisible();
      });

      await test.step('add an asset rule to the draft', async () => {
        await openAddRuleDrawer(page);
        await page.getByTestId('context-rule-name').fill(ruleName);
        const saved = await saveRuleToDraft(page);

        expect(
          (
            saved.request().postDataJSON() as {
              context: { rules: { name: string }[] };
            }
          ).context.rules.map(({ name }) => name)
        ).toContain(ruleName);
        await expect(
          page.getByTestId('context-rule-card').filter({ hasText: ruleName })
        ).toBeVisible();
        await expect(page.getByTestId('publish-harness')).toBeEnabled();
      });

      await test.step('publish the draft', async () => {
        const published = waitForHarnessPublish(page);
        await page.getByTestId('publish-harness').click();
        await published;

        await expect(page.getByTestId('harness-save-state')).toContainText(
          'Published v'
        );
        await expect(page.getByTestId('publish-harness')).toBeDisabled();

        const { apiContext, afterAction } = await getDefaultAdminAPIContext(
          browser
        );
        const state = await getPersonaHarness(
          apiContext,
          persona.responseData.fullyQualifiedName
        );
        await afterAction();

        expect(state.status).toBe(HarnessStatus.Published);
        expect(state.draft).toBeUndefined();
        expect(
          (state.published.context?.rules ?? []).map(({ name }) => name)
        ).toContain(ruleName);
      });

      await test.step('download the Claude plugin and check its files', async () => {
        await page.getByTestId('harness-tab-surfaces').click();
        await page.getByTestId('generate-plugin').click();
        await expect(page.getByTestId('plugin-file-tree')).toContainText(
          'openmetadata.zip'
        );

        const downloading = page.waitForEvent('download');
        const served = waitForResponseWithStatus(
          page,
          (response) =>
            new URL(response.url()).pathname === '/api/v1/agentHarness/plugin',
          200
        );
        await page.getByTestId('download-plugin').click();
        const [download] = await Promise.all([downloading, served]);
        await expect(page.getByTestId('download-plugin')).toContainText(
          'Downloaded'
        );

        expect(download.suggestedFilename()).toBe('openmetadata.zip');
        const path = await download.path();
        if (!path) {
          throw new Error('The plugin download was not saved');
        }
        expect(zipEntryNames(await readFile(path)).sort()).toEqual(
          PLUGIN_ENTRIES
        );
      });
    });
  }
);
