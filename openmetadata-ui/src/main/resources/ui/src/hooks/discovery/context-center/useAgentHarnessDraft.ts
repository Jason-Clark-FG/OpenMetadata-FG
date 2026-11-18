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
import { AxiosError, isAxiosError } from 'axios';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { DRAFT_SAVE_DELAY_MS } from '../../../constants/discovery/agentHarness.constants';
import {
  AgentHarnessSpec,
  AgentHarnessState,
} from '../../../generated/api/context/agentHarnessState';
import {
  DeliveryRow,
  HarnessBudgetErrors,
  HarnessTarget,
} from '../../../interface/discovery/agentHarness.interface';
import {
  getDefaultAgentHarness,
  getPersonaAgentHarness,
  publishAgentHarness,
  putAgentHarnessDraft,
  setAgentHarnessEnabled,
} from '../../../rest/AgentHarnessAPI';
import {
  budgetErrors,
  deliveryRows,
  editableSpec,
  hasBudgetErrors,
} from '../../../utils/discovery/context-center/agentHarness.utils';
import { showErrorToast, showInfoToast } from '../../../utils/ToastUtils';

/**
 * Where the local spec stands against the server draft. `invalid` holds an
 * edit back because the server would reject it; `failed` is a save the server
 * refused for another reason.
 */
export type DraftSaveState =
  | 'saved'
  | 'pending'
  | 'saving'
  | 'invalid'
  | 'failed';

export type HarnessLoad =
  | { kind: 'loading' }
  | { kind: 'missing' }
  | { kind: 'error' }
  | { kind: 'ready'; state: AgentHarnessState; spec: AgentHarnessSpec };

export interface AgentHarnessDraft {
  load: HarnessLoad;
  saveState: DraftSaveState;
  rows: DeliveryRow[];
  errors: HarnessBudgetErrors;
  isPublishing: boolean;
  update: (change: (spec: AgentHarnessSpec) => AgentHarnessSpec) => void;
  /** Saves a pending edit now; resolves true once the server holds the local spec. */
  flush: () => Promise<boolean>;
  publish: () => Promise<void>;
  setEnabled: (enabled: boolean) => Promise<void>;
  reload: () => Promise<void>;
}

const PRECONDITION_FAILED = 412;
const NOT_FOUND = 404;

const fetchState = (target: HarnessTarget) =>
  target.kind === 'default'
    ? getDefaultAgentHarness()
    : getPersonaAgentHarness(target.personaFqn);

/**
 * The editor's draft: the local spec is the source of truth while editing, and
 * each change is written to the server draft after a pause, guarded by the
 * draft revision so two editors never overwrite each other silently.
 */
export const useAgentHarnessDraft = (
  target: HarnessTarget
): AgentHarnessDraft => {
  const { t } = useTranslation();
  const [load, setLoad] = useState<HarnessLoad>({ kind: 'loading' });
  const [saveState, setSaveState] = useState<DraftSaveState>('saved');
  const [isPublishing, setIsPublishing] = useState(false);
  const loadRef = useRef(load);
  const editVersion = useRef(0);
  const savedVersion = useRef(0);
  const timer = useRef<number>();
  const inFlight = useRef<Promise<boolean>>();
  const targetKey =
    target.kind === 'default' ? 'default' : `persona:${target.personaFqn}`;
  const targetRef = useRef(target);
  targetRef.current = target;

  const commit = useCallback((next: HarnessLoad) => {
    loadRef.current = next;
    setLoad(next);
  }, []);

  const reload = useCallback(async () => {
    window.clearTimeout(timer.current);
    try {
      const state = await fetchState(targetRef.current);
      editVersion.current = 0;
      savedVersion.current = 0;
      commit({ kind: 'ready', spec: editableSpec(state), state });
      setSaveState('saved');
    } catch (error) {
      if (isAxiosError(error) && error.response?.status === NOT_FOUND) {
        commit({ kind: 'missing' });
      } else {
        commit({ kind: 'error' });
        showErrorToast(error as AxiosError);
      }
    }
  }, [commit]);

  useEffect(() => {
    commit({ kind: 'loading' });
    void reload();

    return () => window.clearTimeout(timer.current);
  }, [targetKey]);

  const save = useCallback(async (): Promise<boolean> => {
    const current = loadRef.current;
    if (current.kind !== 'ready') {
      return false;
    }
    const version = editVersion.current;
    const { spec, state } = current;
    if (
      hasBudgetErrors(budgetErrors(spec, deliveryRows(spec, state.resolved)))
    ) {
      setSaveState('invalid');

      return false;
    }
    setSaveState('saving');
    try {
      const next = await putAgentHarnessDraft(
        state.published.id,
        state.draftRevision,
        spec
      );
      savedVersion.current = version;
      const latest = loadRef.current;
      if (latest.kind === 'ready') {
        commit({ ...latest, state: next });
      }
      const caughtUp = editVersion.current === version;
      setSaveState(caughtUp ? 'saved' : 'pending');

      return caughtUp;
    } catch (error) {
      if (
        isAxiosError(error) &&
        error.response?.status === PRECONDITION_FAILED
      ) {
        showInfoToast(t('message.agent-harness-changed-elsewhere'));
        await reload();
      } else {
        setSaveState('failed');
        showErrorToast(error as AxiosError);
      }

      return false;
    }
  }, [commit, reload, t]);

  const flush = useCallback(async (): Promise<boolean> => {
    window.clearTimeout(timer.current);
    while (inFlight.current) {
      await inFlight.current;
    }
    if (editVersion.current === savedVersion.current) {
      return loadRef.current.kind === 'ready';
    }
    const running = save();
    inFlight.current = running;
    try {
      return await running;
    } finally {
      inFlight.current = undefined;
    }
  }, [save]);

  const update = useCallback(
    (change: (spec: AgentHarnessSpec) => AgentHarnessSpec) => {
      const current = loadRef.current;
      if (current.kind !== 'ready') {
        return;
      }
      editVersion.current += 1;
      commit({ ...current, spec: change(current.spec) });
      setSaveState('pending');
      window.clearTimeout(timer.current);
      timer.current = window.setTimeout(() => {
        void flush();
      }, DRAFT_SAVE_DELAY_MS);
    },
    [commit, flush]
  );

  const publish = useCallback(async () => {
    setIsPublishing(true);
    try {
      if (!(await flush())) {
        return;
      }
      const current = loadRef.current;
      if (current.kind !== 'ready') {
        return;
      }
      const next = await publishAgentHarness(
        current.state.published.id,
        current.state.draftRevision
      );
      commit({ kind: 'ready', spec: editableSpec(next), state: next });
    } catch (error) {
      if (
        isAxiosError(error) &&
        error.response?.status === PRECONDITION_FAILED
      ) {
        showInfoToast(t('message.agent-harness-changed-elsewhere'));
        await reload();
      } else {
        showErrorToast(error as AxiosError);
      }
    } finally {
      setIsPublishing(false);
    }
  }, [commit, flush, reload, t]);

  const setEnabled = useCallback(
    async (enabled: boolean) => {
      const current = loadRef.current;
      if (current.kind !== 'ready') {
        return;
      }
      try {
        const next = await setAgentHarnessEnabled(
          current.state.published.id,
          enabled
        );
        const latest = loadRef.current;
        if (latest.kind === 'ready') {
          commit({ ...latest, state: next });
        }
      } catch (error) {
        showErrorToast(error as AxiosError);
      }
    },
    [commit]
  );

  const { rows, errors } = useMemo(() => {
    if (load.kind !== 'ready') {
      return { errors: { events: {} }, rows: [] };
    }
    const delivery = deliveryRows(load.spec, load.state.resolved);

    return { errors: budgetErrors(load.spec, delivery), rows: delivery };
  }, [load]);

  return {
    errors,
    flush,
    isPublishing,
    load,
    publish,
    reload,
    rows,
    saveState,
    setEnabled,
    update,
  };
};
