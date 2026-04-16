import { create } from 'zustand';
import { subscribeWithSelector } from 'zustand/middleware';
import type {
  ChatErrorPayload,
  ChatReplyPayload,
  SpeechMode,
  SpeechTranscriptPayload,
} from '../types/schema';
import type { AiCenterChatMessage } from '../types/aiCenter';
import { chatWsService } from '../services/chatWsService';
import { useRiskStore } from './useRiskStore';
import type { DisplayConnectionState } from '../types/connection';

export type VoiceCaptureState = 'idle' | 'recording' | 'transcribing' | 'sent' | 'error';

interface SendSpeechMessageOptions {
  audioData: string;
  audioFormat: string;
  mode: SpeechMode;
}

interface AiCenterState {
  aiCenterOpenRequestVersion: number;
  conversationId: string;
  chatMessages: AiCenterChatMessage[];
  chatInput: string;
  editingMessageEventId: string | null;
  editingDraft: string;
  editingBaselineUserContent: string | null;
  editingSubmitEventId: string | null;
  editingSubmitError: string | null;
  pendingChatEventIds: Record<string, boolean>;
  chatErrorByEventId: Record<string, string | null>;
  latestChatError: ChatErrorPayload | null;

  chatConnectionState: DisplayConnectionState;

  speechEnabled: boolean;
  speechUnlocked: boolean;
  speechSupported: boolean;
  spokenMessages: Record<string, string>;
  lastSpokenAt: Record<string, number>;

  voiceCaptureSupported: boolean;
  voiceCaptureState: VoiceCaptureState;
  voiceCaptureError: string | null;
  activeVoiceEventId: string | null;
  activeVoiceMode: SpeechMode | null;

  isChatFocused: boolean;

  setChatInput: (value: string) => void;
  sendTextMessage: (content: string) => boolean;
  sendSpeechMessage: (options: SendSpeechMessageOptions) => boolean;
  startEditingLastUserMessage: () => boolean;
  updateEditingDraft: (value: string) => void;
  cancelEditingLastUserMessage: () => void;
  confirmEditingLastUserMessage: () => boolean;
  clearEditingSubmitError: () => void;
  appendChatReply: (payload: ChatReplyPayload) => void;
  appendSpeechTranscript: (payload: SpeechTranscriptPayload) => void;
  appendChatError: (payload: ChatErrorPayload) => void;
  setChatConnectionState: (state: DisplayConnectionState) => void;
  setSpeechEnabled: (enabled: boolean) => void;
  setSpeechUnlocked: (unlocked: boolean) => void;
  setSpeechSupported: (supported: boolean) => void;
  markMessageSpoken: (messageKey: string, text: string) => void;
  reset: () => void;
  resetConversation: () => void;
  setVoiceCaptureSupported: (supported: boolean) => void;
  setVoiceCaptureRecording: () => void;
  setVoiceCaptureTranscribing: (eventId: string, mode: SpeechMode) => void;
  setVoiceCaptureSent: (eventId?: string) => void;
  setVoiceCaptureError: (error: string, eventId?: string | null) => void;
  resetVoiceCapture: () => void;
  setIsChatFocused: (isFocused: boolean) => void;
  requestAiCenterOpen: () => void;
}

const initialState = () => ({
  aiCenterOpenRequestVersion: 0,
  conversationId: createConversationId(),
  chatMessages: [] as AiCenterChatMessage[],
  chatInput: '',
  editingMessageEventId: null as string | null,
  editingDraft: '',
  editingBaselineUserContent: null as string | null,
  editingSubmitEventId: null as string | null,
  editingSubmitError: null as string | null,
  pendingChatEventIds: {} as Record<string, boolean>,
  chatErrorByEventId: {} as Record<string, string | null>,
  latestChatError: null as ChatErrorPayload | null,
  chatConnectionState: 'disconnected' as DisplayConnectionState,
  speechEnabled: false,
  speechUnlocked: false,
  speechSupported: false,
  spokenMessages: {} as Record<string, string>,
  lastSpokenAt: {} as Record<string, number>,
  voiceCaptureSupported: false,
  voiceCaptureState: 'idle' as VoiceCaptureState,
  voiceCaptureError: null as string | null,
  activeVoiceEventId: null as string | null,
  activeVoiceMode: null as SpeechMode | null,
  isChatFocused: false,
});

export const useAiCenterStore = create<AiCenterState>()(
  subscribeWithSelector((set, get) => ({
    ...initialState(),

    setChatInput: (value: string) => {
      set({ chatInput: value });
    },

    sendTextMessage: (content: string) => {
      const text = content.trim();
      if (!text) {
        return false;
      }
      if (hasPendingConversationRequest(get())) {
        return false;
      }

      const conversationId = get().conversationId;
      const selectedTargetIds = useRiskStore.getState().selectedTargetIds;
      const eventId = chatWsService.send('CHAT', {
        conversation_id: conversationId,
        content: text,
        ...(selectedTargetIds.length > 0 && { selected_target_ids: selectedTargetIds }),
      });

      if (!eventId) {
        return false;
      }

      set((state) => ({
        chatMessages: [
          ...state.chatMessages,
          createUserChatMessage({
            eventId,
            conversationId,
            content: text,
            requestType: 'CHAT',
          }),
        ],
        chatInput: '',
        pendingChatEventIds: {
          ...state.pendingChatEventIds,
          [eventId]: true,
        },
        chatErrorByEventId: {
          ...state.chatErrorByEventId,
          [eventId]: null,
        },
        ...createClearedEditingState(),
      }));

      return true;
    },

    sendSpeechMessage: ({ audioData, audioFormat, mode }: SendSpeechMessageOptions) => {
      const trimmedAudio = audioData.trim();
      const trimmedFormat = audioFormat.trim();
      if (!trimmedAudio || !trimmedFormat) {
        return false;
      }
      if (hasPendingConversationRequest(get())) {
        return false;
      }

      const conversationId = get().conversationId;
      const selectedTargetIds = useRiskStore.getState().selectedTargetIds;
      const eventId = chatWsService.send('SPEECH', {
        conversation_id: conversationId,
        audio_data: trimmedAudio,
        audio_format: trimmedFormat,
        mode,
        ...(selectedTargetIds.length > 0 && { selected_target_ids: selectedTargetIds }),
      });

      if (!eventId) {
        return false;
      }

      set((state) => ({
        chatMessages: mode === 'preview'
          ? state.chatMessages
          : [
              ...state.chatMessages,
              createUserChatMessage({
                eventId,
                conversationId,
                content: '语音请求已发送',
                requestType: 'SPEECH',
                speechMode: mode,
                audioFormat: trimmedFormat,
              }),
            ],
        pendingChatEventIds: {
          ...state.pendingChatEventIds,
          [eventId]: true,
        },
        chatErrorByEventId: {
          ...state.chatErrorByEventId,
          [eventId]: null,
        },
        voiceCaptureState: 'transcribing',
        voiceCaptureError: null,
        activeVoiceEventId: eventId,
        activeVoiceMode: mode,
        ...createClearedEditingState(),
      }));

      return true;
    },

    startEditingLastUserMessage: () => {
      const state = get();
      if (hasPendingConversationRequest(state) || state.editingMessageEventId || state.editingSubmitEventId) {
        return false;
      }

      const editableTurn = getLastEditableTurn(state.chatMessages);
      if (!editableTurn) {
        return false;
      }

      set({
        editingMessageEventId: editableTurn.userMessage.event_id,
        editingDraft: editableTurn.userMessage.content,
        editingBaselineUserContent: editableTurn.userMessage.content,
        editingSubmitEventId: null,
        editingSubmitError: null,
      });
      return true;
    },

    updateEditingDraft: (value: string) => {
      set({
        editingDraft: value,
      });
    },

    cancelEditingLastUserMessage: () => {
      set({
        ...createClearedEditingState(),
      });
    },

    confirmEditingLastUserMessage: () => {
      const state = get();
      const content = state.editingDraft.trim();
      if (!content || hasPendingConversationRequest(state) || !state.editingMessageEventId) {
        return false;
      }

      const editableTurn = getLastEditableTurn(state.chatMessages);
      if (!editableTurn || editableTurn.userMessage.event_id !== state.editingMessageEventId) {
        return false;
      }

      const conversationId = state.conversationId;
      const selectedTargetIds = useRiskStore.getState().selectedTargetIds;
      const eventId = chatWsService.send('CHAT', {
        conversation_id: conversationId,
        content,
        ...(selectedTargetIds.length > 0 && { selected_target_ids: selectedTargetIds }),
        edit_last_user_message: true,
      });

      if (!eventId) {
        return false;
      }

      set((currentState) => ({
        editingMessageEventId: null,
        editingSubmitEventId: eventId,
        editingSubmitError: null,
        pendingChatEventIds: {
          ...currentState.pendingChatEventIds,
          [eventId]: true,
        },
        chatErrorByEventId: {
          ...currentState.chatErrorByEventId,
          [eventId]: null,
        },
      }));
      return true;
    },

    clearEditingSubmitError: () => {
      set({
        editingSubmitError: null,
      });
    },

    appendChatReply: (payload: ChatReplyPayload) => {
      set((state) => {
        if (payload.conversation_id !== state.conversationId) {
          return state;
        }
        const isEditingSubmit = payload.reply_to_event_id === state.editingSubmitEventId;
        if (state.chatMessages.some((message) => message.event_id === payload.event_id)) {
          return {
            pendingChatEventIds: {
              ...state.pendingChatEventIds,
              [payload.reply_to_event_id]: false,
            },
            chatErrorByEventId: {
              ...state.chatErrorByEventId,
              [payload.reply_to_event_id]: null,
            },
          };
        }

        if (isEditingSubmit) {
          const editableTurn = getLastEditableTurn(state.chatMessages);
          const nextContent = state.editingDraft.trim();
          if (!editableTurn || !nextContent) {
            return {
              pendingChatEventIds: {
                ...state.pendingChatEventIds,
                [payload.reply_to_event_id]: false,
              },
              chatErrorByEventId: {
                ...state.chatErrorByEventId,
                [payload.reply_to_event_id]: null,
              },
              editingSubmitEventId: null,
            };
          }

          const nextMessages = state.chatMessages.map((message, index) => {
            if (index === editableTurn.userIndex) {
              return {
                ...message,
                content: nextContent,
                status: 'replied' as const,
                error_code: undefined,
                error_message: undefined,
              };
            }
            if (index === editableTurn.assistantIndex) {
              return {
                ...message,
                event_id: payload.event_id,
                content: payload.content,
                status: 'sent' as const,
                reply_to_event_id: payload.reply_to_event_id,
                provider: payload.provider,
                timestamp: payload.timestamp,
                error_code: undefined,
                error_message: undefined,
              };
            }
            return message;
          });

          return {
            chatMessages: nextMessages,
            pendingChatEventIds: {
              ...state.pendingChatEventIds,
              [payload.reply_to_event_id]: false,
            },
            chatErrorByEventId: {
              ...state.chatErrorByEventId,
              [payload.reply_to_event_id]: null,
            },
            ...createClearedEditingState(),
          };
        }

        const nextMessages = state.chatMessages.map((message) => (
          message.event_id === payload.reply_to_event_id
            ? {
                ...message,
                status: 'replied' as const,
                error_code: undefined,
                error_message: undefined,
              }
            : message
        ));

        nextMessages.push({
          event_id: payload.event_id,
          conversation_id: payload.conversation_id,
          role: 'assistant',
          content: payload.content,
          status: 'sent',
          reply_to_event_id: payload.reply_to_event_id,
          provider: payload.provider,
          timestamp: payload.timestamp,
          message_type: 'chat_reply',
        });

        return {
          chatMessages: nextMessages,
          pendingChatEventIds: {
            ...state.pendingChatEventIds,
            [payload.reply_to_event_id]: false,
          },
          chatErrorByEventId: {
            ...state.chatErrorByEventId,
            [payload.reply_to_event_id]: null,
          },
        };
      });
    },

    appendSpeechTranscript: (payload: SpeechTranscriptPayload) => {
      const transcript = payload.transcript.trim();
      if (!transcript) {
        return;
      }

      set((state) => {
        if (payload.conversation_id !== state.conversationId) {
          return state;
        }
        const isPreview = state.activeVoiceEventId === payload.reply_to_event_id && state.activeVoiceMode === 'preview';

        return {
          chatMessages: isPreview
            ? state.chatMessages
            : state.chatMessages.map((message) => (
                message.event_id === payload.reply_to_event_id
                  ? {
                      ...message,
                      content: transcript,
                      status: 'sent' as const,
                      transcript_language: payload.language,
                      timestamp: payload.timestamp,
                      error_code: undefined,
                      error_message: undefined,
                    }
                  : message
              )),
          pendingChatEventIds: {
            ...state.pendingChatEventIds,
            [payload.reply_to_event_id]: isPreview ? false : state.pendingChatEventIds[payload.reply_to_event_id] ?? false,
          },
          chatErrorByEventId: {
            ...state.chatErrorByEventId,
            [payload.reply_to_event_id]: null,
          },
          chatInput: isPreview ? transcript : state.chatInput,
        };
      });
    },

    appendChatError: (payload: ChatErrorPayload) => {
      const targetEventId = payload.reply_to_event_id;

      set((state) => {
        if (targetEventId && targetEventId === state.editingSubmitEventId) {
          const editableTurn = getLastEditableTurn(state.chatMessages);
          return {
            latestChatError: payload,
            pendingChatEventIds: {
              ...state.pendingChatEventIds,
              [targetEventId]: false,
            },
            chatErrorByEventId: {
              ...state.chatErrorByEventId,
              [targetEventId]: payload.error_message,
            },
            editingMessageEventId: editableTurn?.userMessage.event_id ?? state.editingMessageEventId,
            editingDraft: state.editingDraft,
            editingBaselineUserContent: editableTurn?.userMessage.content ?? state.editingBaselineUserContent,
            editingSubmitEventId: null,
            editingSubmitError: payload.error_message,
          };
        }

        if (targetEventId) {
          const targetMessageExists = state.chatMessages.some((message) => message.event_id === targetEventId);
          if (!targetMessageExists) {
            return state;
          }
        }

        return {
          latestChatError: payload,
          chatMessages: targetEventId
            ? state.chatMessages.map((message) => (
                message.event_id === targetEventId
                  ? {
                      ...message,
                      status: 'error' as const,
                      error_code: payload.error_code,
                      error_message: payload.error_message,
                    }
                  : message
              ))
            : state.chatMessages,
          pendingChatEventIds: targetEventId
            ? {
                ...state.pendingChatEventIds,
                [targetEventId]: false,
              }
            : state.pendingChatEventIds,
          chatErrorByEventId: targetEventId
            ? {
                ...state.chatErrorByEventId,
                [targetEventId]: payload.error_message,
              }
            : state.chatErrorByEventId,
        };
      });
    },

    setChatConnectionState: (state: DisplayConnectionState) => {
      set({ chatConnectionState: state });
    },

    setSpeechEnabled: (enabled: boolean) => {
      set({ speechEnabled: enabled });
    },

    setSpeechUnlocked: (unlocked: boolean) => {
      set({ speechUnlocked: unlocked });
    },

    setSpeechSupported: (supported: boolean) => {
      set({ speechSupported: supported });
    },

    markMessageSpoken: (messageKey: string, text: string) => {
      set((state) => ({
        spokenMessages: {
          ...state.spokenMessages,
          [messageKey]: text,
        },
        lastSpokenAt: {
          ...state.lastSpokenAt,
          [messageKey]: Date.now(),
        },
      }));
    },

    reset: () => {
      set(initialState());
    },

    resetConversation: () => {
      const oldConversationId = get().conversationId;
      const newConversationId = createConversationId();
      chatWsService.sendClearHistory(oldConversationId);

      set((state) => ({
        conversationId: newConversationId,
        chatMessages: [],
        chatInput: '',
        pendingChatEventIds: {},
        chatErrorByEventId: {},
        latestChatError: null,
        spokenMessages: Object.fromEntries(
          Object.entries(state.spokenMessages).filter(([key]) => key.startsWith('llm-explanation::')),
        ),
        lastSpokenAt: Object.fromEntries(
          Object.entries(state.lastSpokenAt).filter(([key]) => key.startsWith('llm-explanation::')),
        ),
        voiceCaptureState: 'idle',
        voiceCaptureError: null,
        activeVoiceEventId: null,
        activeVoiceMode: null,
        ...createClearedEditingState(),
      }));
    },

    setVoiceCaptureSupported: (supported: boolean) => {
      set({ voiceCaptureSupported: supported });
    },

    setVoiceCaptureRecording: () => {
      set({
        voiceCaptureState: 'recording',
        voiceCaptureError: null,
        activeVoiceEventId: null,
        activeVoiceMode: null,
      });
    },

    setVoiceCaptureTranscribing: (eventId: string, mode: SpeechMode) => {
      set({
        voiceCaptureState: 'transcribing',
        voiceCaptureError: null,
        activeVoiceEventId: eventId,
        activeVoiceMode: mode,
      });
    },

    setVoiceCaptureSent: (eventId?: string) => {
      set((state) => {
        if (eventId && state.activeVoiceEventId && state.activeVoiceEventId !== eventId) {
          return state;
        }

        return {
          voiceCaptureState: 'sent' as VoiceCaptureState,
          voiceCaptureError: null,
          activeVoiceEventId: eventId || state.activeVoiceEventId,
          activeVoiceMode: state.activeVoiceMode,
        };
      });
    },

    setVoiceCaptureError: (error: string, eventId?: string | null) => {
      set({
        voiceCaptureState: 'error',
        voiceCaptureError: error,
        activeVoiceEventId: eventId === undefined ? get().activeVoiceEventId : eventId,
        activeVoiceMode: get().activeVoiceMode,
      });
    },

    resetVoiceCapture: () => {
      set({
        voiceCaptureState: 'idle',
        voiceCaptureError: null,
        activeVoiceEventId: null,
        activeVoiceMode: null,
      });
    },

    setIsChatFocused: (isFocused: boolean) => {
      set({ isChatFocused: isFocused });
    },

    requestAiCenterOpen: () => {
      set((state) => ({
        aiCenterOpenRequestVersion: state.aiCenterOpenRequestVersion + 1,
      }));
    },
  })),
);

export const selectAiCenterOpenRequestVersion = (state: AiCenterState) => state.aiCenterOpenRequestVersion;
export const selectSpeechEnabled = (state: AiCenterState) => state.speechEnabled;
export const selectSpeechSupported = (state: AiCenterState) => state.speechSupported;
export const selectSpeechUnlocked = (state: AiCenterState) => state.speechUnlocked;
export const selectChatMessages = (state: AiCenterState) => state.chatMessages;
export const selectChatInput = (state: AiCenterState) => state.chatInput;
export const selectEditingMessageEventId = (state: AiCenterState) => state.editingMessageEventId;
export const selectEditingDraft = (state: AiCenterState) => state.editingDraft;
export const selectEditingSubmitEventId = (state: AiCenterState) => state.editingSubmitEventId;
export const selectEditingSubmitError = (state: AiCenterState) => state.editingSubmitError;
export const selectConversationId = (state: AiCenterState) => state.conversationId;
export const selectPendingChatEventIds = (state: AiCenterState) => state.pendingChatEventIds;
export const selectChatErrorByEventId = (state: AiCenterState) => state.chatErrorByEventId;
export const selectLatestChatError = (state: AiCenterState) => state.latestChatError;
export const selectIsChatSending = (state: AiCenterState) => hasPendingConversationRequest(state);
export const selectSpokenMessages = (state: AiCenterState) => state.spokenMessages;
export const selectLastSpokenAt = (state: AiCenterState) => state.lastSpokenAt;
export const selectIsChatFocused = (state: AiCenterState) => state.isChatFocused;
export const selectVoiceCaptureSupported = (state: AiCenterState) => state.voiceCaptureSupported;
export const selectVoiceCaptureState = (state: AiCenterState) => state.voiceCaptureState;
export const selectVoiceCaptureError = (state: AiCenterState) => state.voiceCaptureError;
export const selectActiveVoiceEventId = (state: AiCenterState) => state.activeVoiceEventId;
export const selectActiveVoiceMode = (state: AiCenterState) => state.activeVoiceMode;
export const selectChatConnectionState = (state: AiCenterState) => state.chatConnectionState;

let hasInitializedAiCenterStoreSubscriptions = false;

function initializeAiCenterStoreSubscriptions(): void {
  if (hasInitializedAiCenterStoreSubscriptions) {
    return;
  }

  hasInitializedAiCenterStoreSubscriptions = true;

  chatWsService.onChatReply((payload) => {
    const store = useAiCenterStore.getState();
    store.appendChatReply(payload);
    if (store.activeVoiceEventId === payload.reply_to_event_id) {
      store.setVoiceCaptureSent(payload.reply_to_event_id);
    }
  });

  chatWsService.onSpeechTranscript((payload) => {
    const store = useAiCenterStore.getState();
    store.appendSpeechTranscript(payload);
    if (store.activeVoiceEventId === payload.reply_to_event_id) {
      store.setVoiceCaptureSent(payload.reply_to_event_id);
    }
  });

  chatWsService.onError((payload) => {
    const store = useAiCenterStore.getState();
    store.appendChatError(payload);
    if (payload.reply_to_event_id && store.activeVoiceEventId === payload.reply_to_event_id) {
      store.setVoiceCaptureError(payload.error_message, payload.reply_to_event_id);
    }
  });

  chatWsService.onClearHistoryAck((payload) => {
    console.debug('[chatWsService] Clear history acknowledged', payload.conversation_id, payload.reply_to_event_id);
  });

  chatWsService.onConnectionStateChange((state) => {
    useAiCenterStore.getState().setChatConnectionState(state);
  });
}

function createConversationId(): string {
  return `conversation-${crypto.randomUUID()}`;
}

function createUserChatMessage(options: {
  eventId: string;
  conversationId: string;
  content: string;
  requestType: 'CHAT' | 'SPEECH';
  speechMode?: SpeechMode;
  audioFormat?: string;
}): AiCenterChatMessage {
  return {
    event_id: options.eventId,
    conversation_id: options.conversationId,
    role: 'user',
    request_type: options.requestType,
    speech_mode: options.speechMode,
    audio_format: options.audioFormat,
    content: options.content,
    status: 'pending',
    timestamp: new Date().toISOString(),
    message_type: options.requestType === 'SPEECH' ? 'speech_request' : 'chat_user',
  };
}

function createClearedEditingState() {
  return {
    editingMessageEventId: null,
    editingDraft: '',
    editingBaselineUserContent: null,
    editingSubmitEventId: null,
    editingSubmitError: null,
  };
}

function getLastEditableTurn(messages: AiCenterChatMessage[]) {
  if (messages.length < 2) {
    return null;
  }

  const userIndex = messages.length - 2;
  const assistantIndex = messages.length - 1;
  const userMessage = messages[userIndex];
  const assistantMessage = messages[assistantIndex];

  if (userMessage.role !== 'user' || userMessage.request_type !== 'CHAT' || assistantMessage.role !== 'assistant') {
    return null;
  }

  return {
    userIndex,
    assistantIndex,
    userMessage,
    assistantMessage,
  };
}

function hasPendingConversationRequest(state: Pick<AiCenterState, 'pendingChatEventIds'>): boolean {
  return Object.values(state.pendingChatEventIds).some(Boolean);
}

initializeAiCenterStoreSubscriptions();
