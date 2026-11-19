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
import {
  AgentHarnessSpec,
  HarnessStatus,
  MemoryReview,
  MemoryVisibility,
  ToolAccessLevel,
} from '../../../src/generated/api/context/agentHarnessState';
import { expect, test } from '../../support/fixtures/userPages';
import { PersonaClass } from '../../support/persona/PersonaClass';
import {
  createPersonaHarness,
  getPersonaHarness,
  openPersonaHarness,
  waitForHarnessDraftSave,
  waitForHarnessPublish,
} from '../../utils/agentHarness';
import { getDefaultAdminAPIContext } from '../../utils/common';
import { waitForAllLoadersToDisappear } from '../../utils/entity';
import { waitForResponseWithStatus } from '../../utils/waitHelpers';

const draftSpec = (response: Response) =>
  response.request().postDataJSON() as AgentHarnessSpec;

/**
 * Every test edits the same persona's draft, and the draft is written whole against its revision,
 * so the tests run one at a time.
 */
test.describe.serial(
  'Agent Harness — persona harness editor',
  { tag: ['@Features', '@Platform'] },
  () => {
    let persona: PersonaClass;

    const openEditor = (page: Page, tab?: string) =>
      openPersonaHarness(page, persona.responseData.fullyQualifiedName, tab);

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

    test('the cache TTL is written to the draft and reloads from it', async ({
      adminPage: page,
    }) => {
      await openEditor(page);
      const ttl = page.getByTestId('harness-cache-ttl');
      await expect(ttl).toBeEnabled();

      const saved = waitForHarnessDraftSave(page);
      await ttl.fill('45');
      await ttl.press('Tab');

      expect(draftSpec(await saved).context?.cacheTtlMinutes).toBe(45);
      await expect(page.getByTestId('harness-save-state')).toHaveText(
        'Draft has unpublished changes'
      );

      await page.reload({ waitUntil: 'domcontentloaded' });
      await waitForAllLoadersToDisappear(page);

      await expect(page.getByTestId('harness-cache-ttl')).toHaveValue('45');
      await expect(page.getByTestId('harness-save-state')).toHaveText(
        'Draft has unpublished changes'
      );
    });

    test('an out-of-range TTL is held back until it is fixed', async ({
      adminPage: page,
    }) => {
      await openEditor(page);
      const ttl = page.getByTestId('harness-cache-ttl');
      await expect(ttl).toBeEnabled();

      await ttl.fill('5000');
      await ttl.press('Tab');

      await expect(page.getByTestId('harness-save-state')).toHaveText(
        'Fix the budgets to save the draft'
      );
      await expect(page.getByTestId('publish-harness')).toBeDisabled();

      const saved = waitForHarnessDraftSave(page);
      await ttl.fill('30');
      await ttl.press('Tab');

      expect(draftSpec(await saved).context?.cacheTtlMinutes).toBe(30);
      await expect(page.getByTestId('publish-harness')).toBeEnabled();
    });

    test('the preview rail compiles the draft and can be hidden', async ({
      adminPage: page,
    }) => {
      const compiled = waitForResponseWithStatus(
        page,
        (response) =>
          response.request().method() === 'POST' &&
          /^\/api\/v1\/agentHarness\/[^/]+\/preview$/.test(
            new URL(response.url()).pathname
          ),
        200
      );
      await openEditor(page);
      await compiled;

      const rail = page.getByTestId('compiled-context-rail');
      await expect(rail).toBeVisible();
      await expect(rail.getByTestId('compiled-context-tokens')).toBeVisible();

      await page.getByTestId('toggle-preview').click();

      await expect(page.getByTestId('toggle-preview')).toContainText(
        'Show preview'
      );
      await expect(rail).toHaveCount(0);
    });

    test('the persona prompt is written to the draft', async ({
      adminPage: page,
    }) => {
      const prompt = 'You help finance analysts reconcile revenue.';
      await openEditor(page, 'instructions');

      const company = page.getByTestId('company-instructions');
      await expect(company).toContainText('Inherited');
      await expect(company.getByRole('textbox')).toHaveCount(0);

      const saved = waitForHarnessDraftSave(page);
      await page
        .getByTestId('persona-prompt')
        .getByRole('textbox')
        .fill(prompt);

      expect(draftSpec(await saved).context?.prompt).toBe(prompt);
    });

    test('an unnamed MCP server holds back the draft until it is named', async ({
      adminPage: page,
    }) => {
      await openEditor(page, 'guardrails');
      await expect(page.getByTestId('mcp-access')).toBeVisible();

      await page.getByTestId('add-mcp-server').click();

      await expect(page.getByText('Enter the server name')).toBeVisible();
      await expect(page.getByTestId('publish-harness')).toBeDisabled();

      const saved = waitForHarnessDraftSave(page);
      await page.getByTestId('mcp-server-match-0').fill('pw-shadow-db');

      expect(draftSpec(await saved).toolAccess?.servers).toEqual([
        { level: ToolAccessLevel.Block, match: 'pw-shadow-db' },
      ]);
      await expect(page.getByText('Enter the server name')).toHaveCount(0);
      await expect(page.getByTestId('publish-harness')).toBeEnabled();
    });

    test('Author only review keeps memories private', async ({
      adminPage: page,
    }) => {
      await openEditor(page, 'memory');
      await expect(page.getByTestId('memory-review')).toBeVisible();

      const saved = waitForHarnessDraftSave(page);
      await page
        .getByTestId(`memory-review-${MemoryReview.AuthorOnly}`)
        .click();

      const memory = draftSpec(await saved).memory;
      expect(memory?.review).toBe(MemoryReview.AuthorOnly);
      expect(memory?.visibility).toBe(MemoryVisibility.Private);
      await expect(
        page.getByTestId(`memory-visibility-${MemoryVisibility.Organization}`)
      ).toBeDisabled();
      await expect(page.getByTestId('memories-link')).toHaveAttribute(
        'href',
        /\/context-center\/memories\?source=agent$/
      );
    });

    test('publishing makes the draft the published harness', async ({
      adminPage: page,
      browser,
    }) => {
      await openEditor(page);
      const publish = page.getByTestId('publish-harness');
      await expect(publish).toBeEnabled();

      const published = waitForHarnessPublish(page);
      await publish.click();
      await published;

      await expect(page.getByTestId('harness-save-state')).toContainText(
        'Published v'
      );
      await expect(publish).toBeDisabled();

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
      expect(state.published.context?.cacheTtlMinutes).toBe(30);
      expect(state.published.memory?.review).toBe(MemoryReview.AuthorOnly);
      expect(state.published.toolAccess?.servers).toEqual([
        { level: ToolAccessLevel.Block, match: 'pw-shadow-db' },
      ]);
    });
  }
);
