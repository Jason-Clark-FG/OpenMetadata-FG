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
import { ToolAccessServer } from '../../../../generated/api/context/agentHarnessState';
import { McpConnection } from '../../../../interface/discovery/agentHarness.interface';

/** The registered connection a rule's `match` names, compared without regard to case. */
export const findConnection = (
  connections: McpConnection[],
  match: string
): McpConnection | undefined => {
  const name = match.toLowerCase();

  return name
    ? connections.find((connection) => connection.name.toLowerCase() === name)
    : undefined;
};

/** Connections whose name or display name contains the typed text; all of them when it is empty. */
export const filterConnections = (
  connections: McpConnection[],
  text: string
): McpConnection[] => {
  const needle = text.trim().toLowerCase();

  return needle
    ? connections.filter(
        ({ name, displayName }) =>
          name.toLowerCase().includes(needle) ||
          displayName.toLowerCase().includes(needle)
      )
    : connections;
};

/** Registered connections no rule names, so the default decides for them. */
export const uncoveredConnections = (
  connections: McpConnection[],
  rules: ToolAccessServer[]
): McpConnection[] =>
  connections.filter(
    (connection) =>
      !rules.some(
        (rule) => findConnection([connection], rule.match) !== undefined
      )
  );

/** The tools field's value after adding or removing one tool; empty means every tool. */
export const toggleTool = (
  tools: string[] | undefined,
  tool: string,
  selected: boolean
): string[] | undefined => {
  const rest = (tools ?? []).filter((name) => name !== tool);
  const next = selected ? [...rest, tool] : rest;

  return next.length ? next : undefined;
};
