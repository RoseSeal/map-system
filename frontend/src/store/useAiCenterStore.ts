import { create } from 'zustand';
import { subscribeWithSelector } from 'zustand/middleware';
import type {
  ChatErrorPayload,
  ChatInputType,
  ChatMode,
  ChatReplyPayload,
  ChatTranscriptPayload,
  RiskObject,
  Target,
} from '../types/schema';
import type { AiCenterChatMessage, StoredLlmExplanation } from '../types/aiCenter';
import {
  createChatSessionId,
  createUserChatMessage,
  normalizeChatReply,
  normalizeLlmExplanation,
} from '../utils/llmEventNormalizer';

export type VoiceCaptureState = 'idle' | 'recording' | 'transcribing' | 'sent' | 'error';

interface AiCenterState {
  aiCenterOpenRequestVersion: number;
  chatSessionId: string;
  chatMessages: AiCenterChatMessage[];
  chatInput: string;
  pendingChatMessageIds: Record<string, boolean>;
  chatErrorByMessageId: Record<string, string | null>;
  latestLlmExplanations: Record<string, StoredLlmExplanation>;
  readLlmExplanations: Record<string, boolean>;

  speechEnabled: boolean;
  speechUnlocked: boolean;
  speechSupported: boolean;
  spokenMessages: Record<string, string>;
  lastSpokenAt: Record<string, number>;

  voiceCaptureSupported: boolean;
  voiceCaptureState: VoiceCaptureState;
  voiceCaptureError: string | null;
  activeVoiceMessageId: string | null;
  activeVoiceMode: ChatMode | null;

  isChatFocused: boolean;

  ingestRiskObjectForAi: (riskObject: RiskObject, targets: Target[]) => void;
  setChatInput: (value: string) => void;
  appendUserChatMessage: (message: AiCenterChatMessage) => void;
  appendChatReply: (payload: ChatReplyPayload) => void;
  appendChatError: (payload: ChatErrorPayload) => void;
  applyChatTranscript: (payload: ChatTranscriptPayload) => void;
  markChatPending: (messageId: string, pending: boolean) => void;
  markChatMessageError: (messageId: string, errorCode: string, errorMessage: string) => void;
  markLlmRead: (targetId: string) => void;
  setSpeechEnabled: (enabled: boolean) => void;
  setSpeechUnlocked: (unlocked: boolean) => void;
  setSpeechSupported: (supported: boolean) => void;
  markMessageSpoken: (messageKey: string, text: string) => void;
  clearSpokenState: (conversationId?: string) => void;
  resetChatSession: () => void;
  createPendingUserChatMessage: (options: {
    content: string;
    inputType?: ChatInputType;
    chatMode?: ChatMode;
    audioFormat?: string;
  }) => AiCenterChatMessage;
  setVoiceCaptureSupported: (supported: boolean) => void;
  setVoiceCaptureRecording: () => void;
  setVoiceCaptureTranscribing: (messageId: string, mode: ChatMode) => void;
  setVoiceCaptureSent: (messageId?: string) => void;
  setVoiceCaptureError: (error: string, messageId?: string | null) => void;
  resetVoiceCapture: () => void;
  setIsChatFocused: (isFocused: boolean) => void;
  requestAiCenterOpen: () => void;
}

const initialState = () => ({
  aiCenterOpenRequestVersion: 0,
  chatSessionId: createChatSessionId(),
  chatMessages: [] as AiCenterChatMessage[],
  chatInput: '',
  pendingChatMessageIds: {} as Record<string, boolean>,
  chatErrorByMessageId: {} as Record<string, string | null>,
  latestLlmExplanations: {} as Record<string, StoredLlmExplanation>,
  readLlmExplanations: {} as Record<string, boolean>,
  speechEnabled: false,
  speechUnlocked: false,
  speechSupported: false,
  spokenMessages: {} as Record<string, string>,
  lastSpokenAt: {} as Record<string, number>,
  voiceCaptureSupported: false,
  voiceCaptureState: 'idle' as VoiceCaptureState,
  voiceCaptureError: null as string | null,
  activeVoiceMessageId: null as string | null,
  activeVoiceMode: null as ChatMode | null,
  isChatFocused: false,
});

export const useAiCenterStore = create<AiCenterState>()(
  subscribeWithSelector((set, get) => ({
    ...initialState(),

    ingestRiskObjectForAi: (riskObject: RiskObject, targets: Target[]) => {
      set((state) => {
        const currentTargetIds = new Set(targets.map((target) => target.id));
        const nextLatestLlmExplanations: Record<string, StoredLlmExplanation> = {};
        const nextReadLlmExplanations: Record<string, boolean> = { ...state.readLlmExplanations };

        targets.forEach((target) => {
          const normalized = normalizeLlmExplanation(riskObject, target, target.risk_assessment.explanation);
          if (normalized) {
            nextLatestLlmExplanations[target.id] = normalized;
            nextReadLlmExplanations[target.id] = state.latestLlmExplanations[target.id]?.message_id === normalized.message_id
              ? state.readLlmExplanations[target.id] ?? false
              : false;
            return;
          }
        });

        Object.keys(nextReadLlmExplanations).forEach((targetId) => {
          if (!currentTargetIds.has(targetId)) {
            delete nextReadLlmExplanations[targetId];
          }
        });

        return {
          latestLlmExplanations: nextLatestLlmExplanations,
          readLlmExplanations: nextReadLlmExplanations,
        };
      });
    },

    setChatInput: (value: string) => {
      set({ chatInput: value });
    },

    appendUserChatMessage: (message: AiCenterChatMessage) => {
      set((state) => ({
        chatMessages: [...state.chatMessages, message],
        pendingChatMessageIds: {
          ...state.pendingChatMessageIds,
          [message.message_id]: true,
        },
        chatErrorByMessageId: {
          ...state.chatErrorByMessageId,
          [message.message_id]: null,
        },
      }));
    },

    appendChatReply: (payload: ChatReplyPayload) => {
      const normalized = normalizeChatReply(payload);
      if (!normalized) {
        return;
      }

      set((state) => {
        if (state.chatMessages.some((message) => message.message_id === normalized.message.message_id)) {
          return {
            pendingChatMessageIds: {
              ...state.pendingChatMessageIds,
              [payload.reply_to_message_id]: false,
            },
          };
        }

        const userIndex = state.chatMessages.findIndex((message) => message.message_id === payload.reply_to_message_id);
        const nextMessages = [...state.chatMessages];

        if (userIndex >= 0) {
          nextMessages[userIndex] = {
            ...nextMessages[userIndex],
            status: 'replied',
            error_code: undefined,
            error_message: undefined,
          };
          nextMessages.splice(userIndex + 1, 0, normalized.message);
        } else {
          nextMessages.push(normalized.message);
        }

        return {
          chatMessages: nextMessages,
          pendingChatMessageIds: {
            ...state.pendingChatMessageIds,
            [payload.reply_to_message_id]: false,
          },
          chatErrorByMessageId: {
            ...state.chatErrorByMessageId,
            [payload.reply_to_message_id]: null,
          },
        };
      });
    },

    appendChatError: (payload: ChatErrorPayload) => {
      const targetMessageId = payload.reply_to_message_id;
      if (!targetMessageId) {
        return;
      }

      set((state) => ({
        chatMessages: state.chatMessages.map((message) => (
          message.message_id === targetMessageId
            ? {
                ...message,
                status: 'error',
                error_code: payload.error_code,
                error_message: payload.error_message,
              }
            : message
        )),
        pendingChatMessageIds: {
          ...state.pendingChatMessageIds,
          [targetMessageId]: false,
        },
        chatErrorByMessageId: {
          ...state.chatErrorByMessageId,
          [targetMessageId]: payload.error_message,
        },
      }));
    },

    applyChatTranscript: (payload: ChatTranscriptPayload) => {
      const transcript = payload.transcript.trim();
      if (!transcript) {
        return;
      }

      set((state) => {
        const isPreview = state.activeVoiceMessageId === payload.reply_to_message_id && state.activeVoiceMode === 'preview';

        return {
          chatMessages: isPreview
            ? state.chatMessages
            : state.chatMessages.map((message) => (
                message.message_id === payload.reply_to_message_id
                  ? {
                      ...message,
                      content: transcript,
                      transcript_language: payload.language,
                      error_code: undefined,
                      error_message: undefined,
                    }
                  : message
              )),
          pendingChatMessageIds: isPreview
            ? {
                ...state.pendingChatMessageIds,
                [payload.reply_to_message_id]: false,
              }
            : state.pendingChatMessageIds,
          chatErrorByMessageId: {
            ...state.chatErrorByMessageId,
            [payload.reply_to_message_id]: null,
          },
          chatInput: isPreview ? transcript : state.chatInput,
        };
      });
    },

    markChatPending: (messageId: string, pending: boolean) => {
      set((state) => ({
        pendingChatMessageIds: {
          ...state.pendingChatMessageIds,
          [messageId]: pending,
        },
        chatMessages: state.chatMessages.map((message) => (
          message.message_id === messageId
            ? {
                ...message,
                status: pending ? 'pending' : message.status,
              }
            : message
        )),
      }));
    },

    markChatMessageError: (messageId: string, errorCode: string, errorMessage: string) => {
      set((state) => ({
        chatMessages: state.chatMessages.map((message) => (
          message.message_id === messageId
            ? {
                ...message,
                status: 'error',
                error_code: errorCode,
                error_message: errorMessage,
              }
            : message
        )),
        pendingChatMessageIds: {
          ...state.pendingChatMessageIds,
          [messageId]: false,
        },
        chatErrorByMessageId: {
          ...state.chatErrorByMessageId,
          [messageId]: errorMessage,
        },
      }));
    },

    markLlmRead: (targetId: string) => {
      set((state) => ({
        readLlmExplanations: {
          ...state.readLlmExplanations,
          [targetId]: true,
        },
      }));
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

    clearSpokenState: (conversationId?: string) => {
      set((state) => {
        if (!conversationId) {
          return {
            spokenMessages: {},
            lastSpokenAt: {},
          };
        }

        const spokenMessages = Object.fromEntries(
          Object.entries(state.spokenMessages).filter(([key]) => !key.startsWith(`${conversationId}::`)),
        );
        const lastSpokenAt = Object.fromEntries(
          Object.entries(state.lastSpokenAt).filter(([key]) => !key.startsWith(`${conversationId}::`)),
        );

        return {
          spokenMessages,
          lastSpokenAt,
        };
      });
    },

    resetChatSession: () => {
      set((state) => ({
        chatSessionId: createChatSessionId(),
        chatMessages: [],
        chatInput: '',
        pendingChatMessageIds: {},
        chatErrorByMessageId: {},
        spokenMessages: Object.fromEntries(
          Object.entries(state.spokenMessages).filter(([key]) => key.startsWith('llm-explanation::')),
        ),
        lastSpokenAt: Object.fromEntries(
          Object.entries(state.lastSpokenAt).filter(([key]) => key.startsWith('llm-explanation::')),
        ),
        voiceCaptureState: 'idle',
        voiceCaptureError: null,
        activeVoiceMessageId: null,
        activeVoiceMode: null,
      }));
    },

    createPendingUserChatMessage: (options) => {
      return createUserChatMessage(get().chatSessionId, options);
    },

    setVoiceCaptureSupported: (supported: boolean) => {
      set({ voiceCaptureSupported: supported });
    },

    setVoiceCaptureRecording: () => {
      set({
        voiceCaptureState: 'recording',
        voiceCaptureError: null,
        activeVoiceMessageId: null,
        activeVoiceMode: null,
      });
    },

    setVoiceCaptureTranscribing: (messageId: string, mode: ChatMode) => {
      set({
        voiceCaptureState: 'transcribing',
        voiceCaptureError: null,
        activeVoiceMessageId: messageId,
        activeVoiceMode: mode,
      });
    },

    setVoiceCaptureSent: (messageId?: string) => {
      set((state) => {
        if (messageId && state.activeVoiceMessageId && state.activeVoiceMessageId !== messageId) {
          return state;
        }

        return {
          voiceCaptureState: 'sent' as VoiceCaptureState,
          voiceCaptureError: null,
          activeVoiceMessageId: messageId || state.activeVoiceMessageId,
          activeVoiceMode: state.activeVoiceMode,
        };
      });
    },

    setVoiceCaptureError: (error: string, messageId?: string | null) => {
      set({
        voiceCaptureState: 'error',
        voiceCaptureError: error,
        activeVoiceMessageId: messageId === undefined ? get().activeVoiceMessageId : messageId,
        activeVoiceMode: get().activeVoiceMode,
      });
    },

    resetVoiceCapture: () => {
      set({
        voiceCaptureState: 'idle',
        voiceCaptureError: null,
        activeVoiceMessageId: null,
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
export const selectLatestLlmExplanations = (state: AiCenterState) => state.latestLlmExplanations;
export const selectReadLlmExplanations = (state: AiCenterState) => state.readLlmExplanations;
export const selectSpeechEnabled = (state: AiCenterState) => state.speechEnabled;
export const selectSpeechSupported = (state: AiCenterState) => state.speechSupported;
export const selectSpeechUnlocked = (state: AiCenterState) => state.speechUnlocked;
export const selectChatMessages = (state: AiCenterState) => state.chatMessages;
export const selectChatInput = (state: AiCenterState) => state.chatInput;
export const selectChatSessionId = (state: AiCenterState) => state.chatSessionId;
export const selectPendingChatMessageIds = (state: AiCenterState) => state.pendingChatMessageIds;
export const selectChatErrorByMessageId = (state: AiCenterState) => state.chatErrorByMessageId;
export const selectIsChatSending = (state: AiCenterState) => state.chatMessages.some(
  (message) => message.role === 'user' && message.input_type !== 'SPEECH' && state.pendingChatMessageIds[message.message_id],
);
export const selectSpokenMessages = (state: AiCenterState) => state.spokenMessages;
export const selectLastSpokenAt = (state: AiCenterState) => state.lastSpokenAt;
export const selectIsChatFocused = (state: AiCenterState) => state.isChatFocused;
export const selectVoiceCaptureSupported = (state: AiCenterState) => state.voiceCaptureSupported;
export const selectVoiceCaptureState = (state: AiCenterState) => state.voiceCaptureState;
export const selectVoiceCaptureError = (state: AiCenterState) => state.voiceCaptureError;
export const selectActiveVoiceMessageId = (state: AiCenterState) => state.activeVoiceMessageId;
export const selectActiveVoiceMode = (state: AiCenterState) => state.activeVoiceMode;
