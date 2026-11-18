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
import { expect, test } from '../../support/fixtures/userPages';
import { PersonaClass } from '../../support/persona/PersonaClass';
import {
  agentHarnessPersonaRoute,
  AGENT_HARNESS_LIST_ROUTE,
  createPersonaHarness,
  openPersonaHarness,
} from '../../utils/agentHarness';
import { getDefaultAdminAPIContext } from '../../utils/common';
import { waitForAllLoadersToDisappear } from '../../utils/entity';

const RETIRED_AI_CONTEXT_ROUTE = '/context-center/ai-context';

test.describe(
  'Agent Harness — navigation',
  { tag: ['@Features', '@Platform'] },
  () => {
    let persona: PersonaClass;

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

    test('the retired AI Context list URL lands on the harness list', async ({
      adminPage: page,
    }) => {
      await page.goto(RETIRED_AI_CONTEXT_ROUTE, {
        waitUntil: 'domcontentloaded',
      });

      await expect(page.getByTestId('agent-harness-list-page')).toBeVisible();
      await expect(page).toHaveURL(
        (url) => url.pathname === AGENT_HARNESS_LIST_ROUTE
      );
    });

    test("the retired AI Context persona URL lands on that persona's harness", async ({
      adminPage: page,
    }) => {
      const fqn = persona.responseData.fullyQualifiedName;
      await page.goto(
        `${RETIRED_AI_CONTEXT_ROUTE}/${encodeURIComponent(fqn)}`,
        { waitUntil: 'domcontentloaded' }
      );
      await waitForAllLoadersToDisappear(page);

      await expect(page.getByTestId('agent-harness-editor-page')).toBeVisible();
      await expect(page).toHaveURL(
        (url) =>
          `${url.pathname}${url.search}` === agentHarnessPersonaRoute(fqn)
      );
      await expect(page.getByTestId('context-tab')).toBeVisible();
    });

    test('the tab named in the URL opens and survives a reload', async ({
      adminPage: page,
    }) => {
      await openPersonaHarness(
        page,
        persona.responseData.fullyQualifiedName,
        'guardrails'
      );

      await expect(page.getByTestId('guardrails-tab')).toBeVisible();
      await expect(page.getByTestId('harness-tab-guardrails')).toHaveAttribute(
        'aria-selected',
        'true'
      );

      await page.reload({ waitUntil: 'domcontentloaded' });
      await waitForAllLoadersToDisappear(page);

      await expect(page.getByTestId('guardrails-tab')).toBeVisible();
      await expect(page).toHaveURL(
        (url) => url.searchParams.get('tab') === 'guardrails'
      );
    });

    test('choosing a tab writes it to the URL, so a reload keeps it', async ({
      adminPage: page,
    }) => {
      await openPersonaHarness(page, persona.responseData.fullyQualifiedName);
      await expect(page.getByTestId('context-tab')).toBeVisible();

      await page.getByTestId('harness-tab-memory').click();

      await expect(page.getByTestId('memory-capture')).toBeVisible();
      await expect(page).toHaveURL(
        (url) => url.searchParams.get('tab') === 'memory'
      );

      await page.reload({ waitUntil: 'domcontentloaded' });
      await waitForAllLoadersToDisappear(page);

      await expect(page.getByTestId('memory-capture')).toBeVisible();
      await expect(page.getByTestId('harness-tab-memory')).toHaveAttribute(
        'aria-selected',
        'true'
      );
    });

    test('an unknown tab falls back to Context', async ({
      adminPage: page,
    }) => {
      await openPersonaHarness(
        page,
        persona.responseData.fullyQualifiedName,
        'no-such-tab'
      );

      await expect(page.getByTestId('context-tab')).toBeVisible();
      await expect(page.getByTestId('harness-tab-context')).toHaveAttribute(
        'aria-selected',
        'true'
      );
    });
  }
);
