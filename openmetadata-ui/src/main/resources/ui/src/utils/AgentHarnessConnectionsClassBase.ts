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
import { ComponentType } from 'react';
import { McpConnection } from '../interface/discovery/agentHarness.interface';

/** What the Knowledge Graph tab gives every connection-management piece a distribution supplies. */
export interface ConnectionManagementProps {
  readonly canEdit: boolean;
  /**
   * Say the registered connections changed, so the editor reads them again. Pass the name of a
   * connection that was just added and is usable, and the tab switches it on in this harness.
   */
  readonly onConnectionsChange: (addedName?: string) => void;
}

export interface ConnectionRowActionProps extends ConnectionManagementProps {
  /** The registered connection whose row the action sits on. */
  readonly connectionName: string;
}

export interface PendingConnectionsProps extends ConnectionManagementProps {
  /** The connections the harness offers; a new list means they were just read again. */
  readonly connections: McpConnection[];
}

/**
 * Where the connections behind the Knowledge Graph tab are managed. OpenMetadata registers none
 * itself, so it supplies nothing and the Connections section only lists and switches them.
 */
class AgentHarnessConnectionsClassBase {
  /** Rendered in the Connections section's header: a button plus whatever it opens. */
  public getAddConnectionAction(): ComponentType<ConnectionManagementProps> | null {
    return null;
  }

  /** Rendered on each registered connection's row, to manage that connection. */
  public getConnectionRowAction(): ComponentType<ConnectionRowActionProps> | null {
    return null;
  }

  /** Rendered below the list: connections that exist but the harness cannot offer yet. */
  public getPendingConnections(): ComponentType<PendingConnectionsProps> | null {
    return null;
  }
}

const agentHarnessConnectionsClassBase = new AgentHarnessConnectionsClassBase();

export default agentHarnessConnectionsClassBase;
export { AgentHarnessConnectionsClassBase };
