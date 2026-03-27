import { create } from 'zustand';
import { subscribeWithSelector } from 'zustand/middleware';
import type { RiskObject, Target, ChatErrorPayload, ChatInputType, ChatReplyPayload } from '../types/schema';
import type { AiCenterChatMessage, StoredLlmExplanation } from '../types/aiCenter';
import {
  createChatSessionId,
  createUserChatMessage,
  normalizeChatReply,
  normalizeLlmExplanation,
} from '../utils/llmEventNormalizer';

interface AiCenterState {
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

  // 新增：追踪聊天输入框是否处于焦点状态
  isChatFocused: boolean;

  ingestRiskObjectForAi: (riskObject: RiskObject, targets: Target[]) => void;
  setChatInput: (value: string) => void;
  appendUserChatMessage: (message: AiCenterChatMessage) => void;
  appendChatReply: (payload: ChatReplyPayload) => void;
  appendChatError: (payload: ChatErrorPayload) => void;
  markChatPending: (messageId: string, pending: boolean) => void;
  markChatMessageError: (messageId: string, errorCode: string, errorMessage: string) => void;
  markLlmRead: (targetId: string) => void;
  setSpeechEnabled: (enabled: boolean) => void;
  setSpeechUnlocked: (unlocked: boolean) => void;
  setSpeechSupported: (supported: boolean) => void;
  markMessageSpoken: (messageKey: string, text: string) => void;
  clearSpokenState: (conversationId?: string) => void;
  resetChatSession: () => void;
  createPendingUserChatMessage: (content: string, inputType?: ChatInputType) => AiCenterChatMessage;
  // 新增：设置焦点状态的方法
  setIsChatFocused: (isFocused: boolean) => void;
}

const initialState = () => ({
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
  isChatFocused: false, // 初始化
});

export const useAiCenterStore = create<AiCenterState>()(
  subscribeWithSelector((set, get) => ({
    ...initialState(),

    ingestRiskObjectForAi: (riskObject: RiskObject, targets: Target[]) => {
      set((state) => {
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

          delete nextReadLlmExplanations[target.id];
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
      }));
    },

    createPendingUserChatMessage: (content: string, inputType: ChatInputType = 'TEXT') => {
      return createUserChatMessage(get().chatSessionId, content, inputType);
    },

    setIsChatFocused: (isFocused: boolean) => {
      set({ isChatFocused: isFocused });
    },
  }))
);

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
export const selectIsChatSending = (state: AiCenterState) => Object.values(state.pendingChatMessageIds).some(Boolean);
export const selectSpokenMessages = (state: AiCenterState) => state.spokenMessages;
export const selectLastSpokenAt = (state: AiCenterState) => state.lastSpokenAt;
export const selectIsChatFocused = (state: AiCenterState) => state.isChatFocused;