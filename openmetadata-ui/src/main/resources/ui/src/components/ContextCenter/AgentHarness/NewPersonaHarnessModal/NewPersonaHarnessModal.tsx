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
  Button,
  Dialog,
  Modal,
  ModalOverlay,
  RadioButton,
  RadioGroup,
  Typography,
} from '@openmetadata/ui-core-components';
import { AxiosError } from 'axios';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { AgentHarnessPersonaSummary } from '../../../../generated/api/context/agentHarnessPersonaSummary';
import { HarnessStatus } from '../../../../generated/api/context/agentHarnessState';
import {
  createPersonaAgentHarness,
  listAgentHarnessPersonas,
} from '../../../../rest/AgentHarnessAPI';
import { getEntityName } from '../../../../utils/EntityNameUtils';
import { showErrorToast } from '../../../../utils/ToastUtils';
import Loader from '../../../common/Loader/Loader';

/** The server caps a page at 1000; a deployment has far fewer personas. */
const ALL_PERSONAS_LIMIT = 1000;

export interface NewPersonaHarnessModalProps {
  isOpen: boolean;
  onClose: () => void;
  onCreated: (personaFqn: string) => void;
}

const NewPersonaHarnessModal = ({
  isOpen,
  onClose,
  onCreated,
}: NewPersonaHarnessModalProps) => {
  const { t } = useTranslation();
  const [candidates, setCandidates] = useState<AgentHarnessPersonaSummary[]>(
    []
  );
  const [selected, setSelected] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [isCreating, setIsCreating] = useState(false);

  useEffect(() => {
    if (!isOpen) {
      return;
    }
    setSelected(null);
    setIsLoading(true);
    listAgentHarnessPersonas({ limit: ALL_PERSONAS_LIMIT })
      .then(({ data }) =>
        setCandidates(
          data
            .filter(({ status }) => status === HarnessStatus.Inherited)
            .sort((a, b) =>
              getEntityName(a.persona).localeCompare(getEntityName(b.persona))
            )
        )
      )
      .catch((error) => {
        setCandidates([]);
        showErrorToast(error as AxiosError);
      })
      .finally(() => setIsLoading(false));
  }, [isOpen]);

  const handleCreate = async () => {
    const persona = candidates.find(({ persona }) => persona.id === selected);
    if (!persona) {
      return;
    }
    const fqn = persona.persona.fullyQualifiedName ?? persona.persona.name;
    if (!fqn) {
      return;
    }
    setIsCreating(true);
    try {
      await createPersonaAgentHarness(fqn);
      onCreated(fqn);
    } catch (error) {
      showErrorToast(error as AxiosError);
    } finally {
      setIsCreating(false);
    }
  };

  const renderBody = () => {
    if (isLoading) {
      return <Loader size="small" />;
    }
    if (candidates.length === 0) {
      return (
        <Typography className="tw:text-tertiary" size="text-sm">
          {t('message.agent-harness-every-persona-has-harness')}
        </Typography>
      );
    }

    return (
      <RadioGroup
        aria-label={t('label.persona')}
        className="tw:max-h-80 tw:overflow-y-auto"
        data-testid="new-persona-harness-options"
        value={selected}
        onChange={setSelected}>
        {candidates.map(({ persona, userCount }) => (
          <RadioButton
            hint={t('message.agent-harness-persona-member-count', {
              count: userCount,
              name: persona.name,
            })}
            key={persona.id}
            label={getEntityName(persona)}
            value={persona.id}
            variant="card"
          />
        ))}
      </RadioGroup>
    );
  };

  return (
    <ModalOverlay
      isDismissable={!isCreating}
      isOpen={isOpen}
      onOpenChange={(open) => !open && !isCreating && onClose()}>
      <Modal>
        <Dialog
          data-testid="new-persona-harness-modal"
          dividers="scroll"
          width={480}
          onClose={onClose}>
          <Dialog.Header title={t('label.new-persona-harness')} />
          <Dialog.Content>
            <Typography className="tw:mb-4 tw:text-tertiary" size="text-sm">
              {t('message.agent-harness-new-persona-description')}
            </Typography>
            {renderBody()}
          </Dialog.Content>
          <Dialog.Footer>
            <Button
              color="secondary"
              isDisabled={isCreating}
              size="sm"
              onPress={onClose}>
              {t('label.cancel')}
            </Button>
            <Button
              color="primary"
              data-testid="create-persona-harness"
              isDisabled={!selected || isCreating}
              isLoading={isCreating}
              size="sm"
              onPress={handleCreate}>
              {t('label.create')}
            </Button>
          </Dialog.Footer>
        </Dialog>
      </Modal>
    </ModalOverlay>
  );
};

export default NewPersonaHarnessModal;
