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
import {
  APIRequestContext,
  expect,
  Locator,
  Page,
  Response,
} from '@playwright/test';
import {
  AgentHarnessSpec,
  AgentHarnessState,
} from '../../src/generated/api/context/agentHarnessState';
import { okJson } from './apiResponse';
import { waitForAllLoadersToDisappear } from './entity';
import { waitForResponseWithStatus } from './waitHelpers';

// Playwright does not import application routing code outside generated types and enums.
export const AGENT_HARNESS_LIST_ROUTE = '/context-center/agent-harness';
export const AGENT_HARNESS_DEFAULT_ROUTE = `${AGENT_HARNESS_LIST_ROUTE}/default`;
const AGENT_HARNESS_API = '/api/v1/agentHarness';
const MAX_LIST_PAGES = 50;
const ZIP_END_OF_CENTRAL_DIRECTORY = Buffer.from([0x50, 0x4b, 0x05, 0x06]);
const ZIP_CENTRAL_DIRECTORY_ENTRY = 0x02014b50;
const ZIP_CENTRAL_DIRECTORY_HEADER_LENGTH = 46;

export const agentHarnessPersonaRoute = (personaFqn: string, tab?: string) =>
  `${AGENT_HARNESS_LIST_ROUTE}/persona/${encodeURIComponent(personaFqn)}${
    tab ? `?tab=${tab}` : ''
  }`;

const personaStatePath = (personaFqn: string) =>
  `${AGENT_HARNESS_API}/persona/${encodeURIComponent(personaFqn)}`;

const isHarnessCall = (
  response: Response,
  method: string,
  pathPattern: RegExp
) =>
  response.request().method() === method &&
  pathPattern.test(new URL(response.url()).pathname);

/** The debounced draft write the editor sends after an edit. */
export const waitForHarnessDraftSave = (page: Page) =>
  waitForResponseWithStatus(
    page,
    (response) =>
      isHarnessCall(response, 'PUT', /^\/api\/v1\/agentHarness\/[^/]+\/draft$/),
    200
  );

export const waitForHarnessPublish = (page: Page) =>
  waitForResponseWithStatus(
    page,
    (response) =>
      isHarnessCall(
        response,
        'POST',
        /^\/api\/v1\/agentHarness\/[^/]+\/publish$/
      ),
    200
  );

export const createPersonaHarness = async (
  apiContext: APIRequestContext,
  personaFqn: string
): Promise<AgentHarnessState> =>
  okJson<AgentHarnessState>(
    await apiContext.post(personaStatePath(personaFqn)),
    'createPersonaHarness'
  );

export const getPersonaHarness = async (
  apiContext: APIRequestContext,
  personaFqn: string
): Promise<AgentHarnessState> =>
  okJson<AgentHarnessState>(
    await apiContext.get(personaStatePath(personaFqn)),
    'getPersonaHarness'
  );

/** Replaces the harness's draft, as the editor does, against the revision just read. */
export const putPersonaHarnessDraft = async (
  apiContext: APIRequestContext,
  personaFqn: string,
  spec: AgentHarnessSpec
): Promise<AgentHarnessState> => {
  const state = await getPersonaHarness(apiContext, personaFqn);

  return okJson<AgentHarnessState>(
    await apiContext.put(`${AGENT_HARNESS_API}/${state.published.id}/draft`, {
      data: spec,
      headers: { 'If-Match': String(state.draftRevision) },
    }),
    'putPersonaHarnessDraft'
  );
};

/** Opens a persona's harness editor by URL and waits for its state to load. */
export const openPersonaHarness = async (
  page: Page,
  personaFqn: string,
  tab?: string
): Promise<Response> => {
  const loaded = waitForResponseWithStatus(
    page,
    (response) =>
      response.request().method() === 'GET' &&
      new URL(response.url()).pathname === personaStatePath(personaFqn),
    [200, 404]
  );
  await page.goto(agentHarnessPersonaRoute(personaFqn, tab), {
    waitUntil: 'domcontentloaded',
  });
  const response = await loaded;
  await waitForAllLoadersToDisappear(page);
  await expect(page.getByTestId('agent-harness-editor-page')).toBeVisible();

  return response;
};

const isPersonaListResponse = (response: Response) =>
  response.request().method() === 'GET' &&
  new URL(response.url()).pathname === `${AGENT_HARNESS_API}/personas`;

/**
 * Opens the harness list and returns the persona's row, paging forward until it is on screen:
 * the list is cursor-paginated, so a test persona can land past page one.
 */
export const findPersonaHarnessRow = async (
  page: Page,
  personaName: string
): Promise<Locator> => {
  const firstPage = page.waitForResponse(isPersonaListResponse);
  await page.goto(AGENT_HARNESS_LIST_ROUTE, { waitUntil: 'domcontentloaded' });
  await firstPage;
  await waitForAllLoadersToDisappear(page);
  await expect(page.getByTestId('persona-harness-table')).toBeVisible();

  const row = page.getByTestId(`persona-harness-row-${personaName}`);
  const nextPage = page.getByTestId('next');
  for (let pageIndex = 0; pageIndex < MAX_LIST_PAGES; pageIndex++) {
    if ((await row.count()) > 0 || !(await nextPage.isEnabled())) {
      break;
    }
    const nextResponse = page.waitForResponse(isPersonaListResponse);
    await nextPage.click();
    await nextResponse;
    await waitForAllLoadersToDisappear(page);
  }
  await expect(row).toBeVisible();

  return row;
};

export const openAddRuleDrawer = async (page: Page) => {
  await page.getByTestId('add-asset-rule').click();
  await expect(page.getByTestId('form-heading')).toContainText('Add Rule');
};

/** Saves the rule drawer; the rule lands in the local draft, which the editor then writes. */
export const saveRuleToDraft = async (page: Page) => {
  const saved = waitForHarnessDraftSave(page);
  await page.getByRole('button', { name: 'Save Rule' }).click();
  await expect(page.getByTestId('form-heading')).not.toBeVisible();

  return saved;
};

export const enablePersonaRulePreloading = async (
  page: Page
): Promise<void> => {
  const filteredInSearch = page
    .getByTestId('context-rule-filtered-in-search')
    .getByRole('switch');

  await expect(filteredInSearch).toBeChecked();
  await expect(filteredInSearch).toBeEnabled();
  // The sticky drawer footer can overlap the switch after scrolling, so use its
  // accessible keyboard interaction instead of relying on a pointer hit target.
  await filteredInSearch.press('Space');
  await expect(filteredInSearch).not.toBeChecked();
};

/** File names in a zip, read from its central directory. */
export const zipEntryNames = (zip: Buffer): string[] => {
  const end = zip.lastIndexOf(ZIP_END_OF_CENTRAL_DIRECTORY);
  if (end < 0) {
    throw new Error('Not a zip archive');
  }
  const count = zip.readUInt16LE(end + 10);
  let offset = zip.readUInt32LE(end + 16);

  return Array.from({ length: count }, () => {
    if (zip.readUInt32LE(offset) !== ZIP_CENTRAL_DIRECTORY_ENTRY) {
      throw new Error(`No central directory entry at byte ${offset}`);
    }
    const nameLength = zip.readUInt16LE(offset + 28);
    const extraLength = zip.readUInt16LE(offset + 30);
    const commentLength = zip.readUInt16LE(offset + 32);
    const nameStart = offset + ZIP_CENTRAL_DIRECTORY_HEADER_LENGTH;
    offset = nameStart + nameLength + extraLength + commentLength;

    return zip.toString('utf8', nameStart, nameStart + nameLength);
  });
};
