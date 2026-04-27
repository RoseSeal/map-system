import { create } from 'zustand';
import { subscribeWithSelector } from 'zustand/middleware';
import type {
  AgentStepPayload,
  ChatCapabilityPayload,
  ChatErrorPayload,
  ChatReplyPayload,
  SpeechMode,
  SpeechTranscriptPayload,
} from '../types/schema';
import type { AiCenterChatMessage } from '../types/aiCenter';
import { chatWsService } from '../services/chatWsService';
import type { CapabilityState } from '../services/chatWsService';
import { CHAT_CONFIG } from '../config/constants';
import { useRiskStore } from './useRiskStore';
import type { DisplayConnectionState } from '../types/connection';

export type AssistantMode = 'CHAT' | 'AGENT';

export type VoiceCaptureState = 'idle' | 'recording' | 'transcribing' | 'sent' | 'error';

interface SendSpeechMessageOptions {
  audioData: string;
  audioFormat: string;
  mode: SpeechMode;
}

type PendingRequestKind = 'chat' | 'speech';

interface AiCenterState {
  aiCenterOpenRequestVersion: number;
  conversationId: string;
  chatMessages: AiCenterChatMessage[];
  chatInput: string;
  editingMessageEventId: string | null;
  editingDraft: string;
  editingBaselineUserContent: string | null;
  editingBaselineAssistantMessage: AiCenterChatMessage | null;
  editingSubmitEventId: string | null;
  editingSubmitError: string | null;
  pendingChatEventIds: Record<string, boolean>;
  chatErrorByEventId: Record<string, string | null>;
  latestChatError: ChatErrorPayload | null;

  chatConnectionState: DisplayConnectionState;

  assistantMode: AssistantMode;
  chatCapabilityState: CapabilityState;
  chatCapability: ChatCapabilityPayload | null;
  agentStepsByReplyToEventId: Record<string, AgentStepPayload[]>;

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
  setAssistantMode: (mode: AssistantMode) => void;
  setChatCapabilityState: (state: CapabilityState, payload: ChatCapabilityPayload | null) => void;
  appendAgentStep: (payload: AgentStepPayload) => void;
  clearAgentSteps: (replyToEventId: string) => void;
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
  editingBaselineAssistantMessage: null as AiCenterChatMessage | null,
  editingSubmitEventId: null as string | null,
  editingSubmitError: null as string | null,
  pendingChatEventIds: {} as Record<string, boolean>,
  chatErrorByEventId: {} as Record<string, string | null>,
  latestChatError: null as ChatErrorPayload | null,
  chatConnectionState: 'disconnected' as DisplayConnectionState,
  assistantMode: 'CHAT' as AssistantMode,
  chatCapabilityState: 'pending' as CapabilityState,
  chatCapability: null as ChatCapabilityPayload | null,
  agentStepsByReplyToEventId: {} as Record<string, AgentStepPayload[]>,
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

const pendingRequestTimeouts = new Map<string, ReturnType<typeof setTimeout>>();

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
      if (get().chatCapabilityState !== 'ready') {
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
        agent_mode: get().assistantMode,
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
      schedulePendingRequestTimeout(eventId, 'chat');

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
      schedulePendingRequestTimeout(eventId, 'speech');

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
        editingBaselineAssistantMessage: { ...editableTurn.assistantMessage },
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
      const prevEventId = editableTurn.userMessage.event_id;
      get().clearAgentSteps(prevEventId);
      const eventId = chatWsService.send('CHAT', {
        conversation_id: conversationId,
        content,
        agent_mode: state.assistantMode,
        ...(selectedTargetIds.length > 0 && { selected_target_ids: selectedTargetIds }),
        edit_last_user_message: true,
      });

      if (!eventId) {
        return false;
      }

      set((currentState) => {
        const editableTurn = getLastEditableTurn(currentState.chatMessages);
        let nextMessages = currentState.chatMessages;
        if (editableTurn) {
          nextMessages = currentState.chatMessages.map((message, index) => {
            if (index === editableTurn.userIndex) {
              return {
                ...message,
                content,
                status: 'pending' as const,
              };
            }
            if (index === editableTurn.assistantIndex) {
              return {
                ...message,
                status: 'pending' as const,
                content: '正在重新生成回复...',
              };
            }
            return message;
          });
        }

        return {
          chatMessages: nextMessages,
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
        };
      });
      schedulePendingRequestTimeout(eventId, 'chat');
      return true;
    },

    clearEditingSubmitError: () => {
      set({
        editingSubmitError: null,
      });
    },

    appendChatReply: (payload: ChatReplyPayload) => {
      clearPendingRequestTimeout(payload.reply_to_event_id);
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
      clearPendingRequestTimeout(payload.reply_to_event_id);
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
      if (targetEventId) {
        clearPendingRequestTimeout(targetEventId);
      }

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
            chatMessages: editableTurn
              ? state.chatMessages.map((message, index) => {
                  if (index === editableTurn.userIndex) {
                    return {
                      ...message,
                      content: state.editingBaselineUserContent ?? message.content,
                      status: 'replied' as const,
                      error_code: undefined,
                      error_message: undefined,
                    };
                  }
                  if (index === editableTurn.assistantIndex && state.editingBaselineAssistantMessage) {
                    return {
                      ...state.editingBaselineAssistantMessage,
                    };
                  }
                  return message;
                })
              : state.chatMessages,
            editingMessageEventId: editableTurn?.userMessage.event_id ?? state.editingMessageEventId,
            editingDraft: state.editingDraft,
            editingBaselineUserContent: state.editingBaselineUserContent,
            editingBaselineAssistantMessage: state.editingBaselineAssistantMessage,
            editingSubmitEventId: null,
            editingSubmitError: payload.error_message,
          };
        }

        if (targetEventId) {
          const targetMessageExists = state.chatMessages.some((message) => message.event_id === targetEventId);
          if (!targetMessageExists && state.activeVoiceEventId !== targetEventId) {
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
      if (state === 'disconnected') {
        failAllPendingRequests('聊天连接已断开，请检查后端服务或网络连接。', 'CHAT_CHANNEL_UNAVAILABLE');
      }
    },

    setAssistantMode: (mode: AssistantMode) => {
      set({ assistantMode: mode });
    },

    setChatCapabilityState: (capState: CapabilityState, payload: ChatCapabilityPayload | null) => {
      set({
        chatCapabilityState: capState,
        chatCapability: payload ?? null,
      });
    },

    appendAgentStep: (payload: AgentStepPayload) => {
      set((state) => {
        const existing = state.agentStepsByReplyToEventId[payload.reply_to_event_id] ?? [];
        const idx = existing.findIndex((s) => s.step_id === payload.step_id);
        const updated = idx >= 0
          ? existing.map((s, i) => (i === idx ? payload : s))
          : [...existing, payload];
        return {
          agentStepsByReplyToEventId: {
            ...state.agentStepsByReplyToEventId,
            [payload.reply_to_event_id]: updated,
          },
        };
      });
    },

    clearAgentSteps: (replyToEventId: string) => {
      set((state) => {
        if (!(replyToEventId in state.agentStepsByReplyToEventId)) {
          return state;
        }
        const next = { ...state.agentStepsByReplyToEventId };
        delete next[replyToEventId];
        return { agentStepsByReplyToEventId: next };
      });
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
      clearAllPendingRequestTimeouts();
      set(initialState());
    },

    resetConversation: () => {
      const oldConversationId = get().conversationId;
      const newConversationId = createConversationId();
      chatWsService.sendClearHistory(oldConversationId);
      clearAllPendingRequestTimeouts();

      set((state) => ({
        conversationId: newConversationId,
        chatMessages: [],
        chatInput: '',
        pendingChatEventIds: {},
        chatErrorByEventId: {},
        latestChatError: null,
        agentStepsByReplyToEventId: {},
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
export const selectAssistantMode = (state: AiCenterState) => state.assistantMode;
export const selectChatCapabilityState = (state: AiCenterState) => state.chatCapabilityState;
export const selectChatCapability = (state: AiCenterState) => state.chatCapability;
export const selectAgentStepsByReplyToEventId = (state: AiCenterState) => state.agentStepsByReplyToEventId;

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

  chatWsService.onCapabilityState((capState, payload) => {
    useAiCenterStore.getState().setChatCapabilityState(capState, payload);
  });

  chatWsService.onAgentStep((payload) => {
    useAiCenterStore.getState().appendAgentStep(payload);
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
    editingBaselineAssistantMessage: null,
    editingSubmitEventId: null,
    editingSubmitError: null,
  };
}

function schedulePendingRequestTimeout(eventId: string, kind: PendingRequestKind): void {
  clearPendingRequestTimeout(eventId);

  const timeoutMs = kind === 'speech'
    ? CHAT_CONFIG.SPEECH_REQUEST_TIMEOUT_MS
    : CHAT_CONFIG.CHAT_REQUEST_TIMEOUT_MS;

  const timer = setTimeout(() => {
    pendingRequestTimeouts.delete(eventId);

    const store = useAiCenterStore.getState();
    if (!store.pendingChatEventIds[eventId]) {
      return;
    }

    const errorMessage = kind === 'speech'
      ? '语音请求超时，请检查后端服务或网络连接。'
      : 'AI 响应超时，请检查后端服务或网络连接。';
    const errorCode = kind === 'speech' ? 'TRANSCRIPTION_TIMEOUT' : 'CHAT_REQUEST_TIMEOUT';

    failPendingRequest(eventId, errorMessage, errorCode);
  }, timeoutMs);

  pendingRequestTimeouts.set(eventId, timer);
}

function clearPendingRequestTimeout(eventId: string | null | undefined): void {
  if (!eventId) {
    return;
  }

  const timer = pendingRequestTimeouts.get(eventId);
  if (!timer) {
    return;
  }

  clearTimeout(timer);
  pendingRequestTimeouts.delete(eventId);
}

function clearAllPendingRequestTimeouts(): void {
  pendingRequestTimeouts.forEach((timer) => clearTimeout(timer));
  pendingRequestTimeouts.clear();
}

function failPendingRequest(eventId: string, errorMessage: string, errorCode: string): void {
  const store = useAiCenterStore.getState();
  if (!store.pendingChatEventIds[eventId]) {
    clearPendingRequestTimeout(eventId);
    return;
  }

  store.appendChatError({
    event_id: `local-error-${crypto.randomUUID()}`,
    connection: 'chat',
    error_code: errorCode,
    error_message: errorMessage,
    reply_to_event_id: eventId,
    timestamp: new Date().toISOString(),
  });

  if (useAiCenterStore.getState().activeVoiceEventId === eventId) {
    useAiCenterStore.getState().setVoiceCaptureError(errorMessage, eventId);
  }
}

function failAllPendingRequests(errorMessage: string, errorCode: string): void {
  const pendingEventIds = Object.entries(useAiCenterStore.getState().pendingChatEventIds)
    .filter(([, pending]) => pending)
    .map(([eventId]) => eventId);

  pendingEventIds.forEach((eventId) => {
    failPendingRequest(eventId, errorMessage, errorCode);
  });
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
