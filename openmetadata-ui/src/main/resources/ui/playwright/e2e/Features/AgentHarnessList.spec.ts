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
import { UserClass } from '../../support/user/UserClass';
import {
  agentHarnessPersonaRoute,
  AGENT_HARNESS_LIST_ROUTE,
  createPersonaHarness,
  findPersonaHarnessRow,
  getPersonaHarness,
} from '../../utils/agentHarness';
import { getDefaultAdminAPIContext } from '../../utils/common';
import { waitForAllLoadersToDisappear } from '../../utils/entity';
import { waitForResponseWithStatus } from '../../utils/waitHelpers';

const ASSIGNED_USER_COUNT = 2;

test.describe.serial(
  'Agent Harness — persona list',
  { tag: ['@Features', '@Platform'] },
  () => {
    let persona: PersonaClass;
    let assignedUsers: UserClass[];

    test.beforeAll(
      'Create a persona with assigned users and a harness',
      async ({ browser }) => {
        // beforeAll can run more than once per worker, so state is rebuilt here, not at describe scope.
        persona = new PersonaClass();
        assignedUsers = Array.from(
          { length: ASSIGNED_USER_COUNT },
          () => new UserClass()
        );
        const { apiContext, afterAction } = await getDefaultAdminAPIContext(
          browser
        );
        await Promise.all(
          assignedUsers.map((user) => user.create(apiContext, false))
        );
        await persona.create(
          apiContext,
          assignedUsers.map((user) => user.responseData.id)
        );
        await createPersonaHarness(
          apiContext,
          persona.responseData.fullyQualifiedName
        );
        await afterAction();
      }
    );

    test.afterAll('Remove the persona and its users', async ({ browser }) => {
      const { apiContext, afterAction } = await getDefaultAdminAPIContext(
        browser
      );
      await persona.delete(apiContext);
      await Promise.all(assignedUsers.map((user) => user.delete(apiContext)));
      await afterAction();
    });

    test("clicking a row opens that persona's harness", async ({
      adminPage: page,
    }) => {
      const row = await findPersonaHarnessRow(page, persona.data.name);

      await expect(row).toContainText(String(ASSIGNED_USER_COUNT));
      await row.getByText(persona.data.displayName, { exact: true }).click();

      await expect(page.getByTestId('agent-harness-editor-page')).toBeVisible();
      await expect(page).toHaveURL(
        (url) =>
          url.pathname ===
          agentHarnessPersonaRoute(persona.responseData.fullyQualifiedName)
      );
    });

    test('switching a harness off saves it without opening the persona', async ({
      adminPage: page,
      browser,
    }) => {
      const row = await findPersonaHarnessRow(page, persona.data.name);
      const toggle = row.getByTestId(
        `persona-harness-enabled-${persona.data.name}`
      );
      await expect(toggle.getByRole('switch')).toBeChecked();

      const saved = waitForResponseWithStatus(
        page,
        (response) =>
          response.request().method() === 'PUT' &&
          /^\/api\/v1\/agentHarness\/[^/]+\/enabled$/.test(
            new URL(response.url()).pathname
          ),
        200
      );
      // The core Toggle puts its test id on the react-aria label, which takes the press.
      await toggle.click();
      await saved;

      await expect(toggle.getByRole('switch')).not.toBeChecked();
      await expect(page.getByTestId('agent-harness-list-page')).toBeVisible();

      const { apiContext, afterAction } = await getDefaultAdminAPIContext(
        browser
      );
      const state = await getPersonaHarness(
        apiContext,
        persona.responseData.fullyQualifiedName
      );
      await afterAction();

      expect(state.published.enabled).toBe(false);
    });

    test('a non-admin sees neither the list nor a harness', async ({
      dataConsumerPage: page,
    }) => {
      await page.goto(AGENT_HARNESS_LIST_ROUTE, {
        waitUntil: 'domcontentloaded',
      });
      await waitForAllLoadersToDisappear(page);

      await expect(page.getByText('Access Denied')).toBeVisible();
      await expect(page.getByTestId('persona-harness-table')).toHaveCount(0);
      await expect(page.getByTestId('new-persona-harness')).toHaveCount(0);

      await page.goto(
        agentHarnessPersonaRoute(persona.responseData.fullyQualifiedName),
        { waitUntil: 'domcontentloaded' }
      );
      await waitForAllLoadersToDisappear(page);

      await expect(page.getByTestId('agent-harness-editor-page')).toBeVisible();
      await expect(page.getByText('Access Denied')).toBeVisible();
      await expect(page.getByTestId('publish-harness')).toHaveCount(0);
      await expect(page.getByTestId('harness-settings-bar')).toHaveCount(0);
    });
  }
);
