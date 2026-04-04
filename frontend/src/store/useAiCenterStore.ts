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
  pendingChatEventIds: Record<string, boolean>;
  chatErrorByEventId: Record<string, string | null>;
  latestChatError: ChatErrorPayload | null;

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
  appendChatReply: (payload: ChatReplyPayload) => void;
  appendSpeechTranscript: (payload: SpeechTranscriptPayload) => void;
  appendChatError: (payload: ChatErrorPayload) => void;
  setSpeechEnabled: (enabled: boolean) => void;
  setSpeechUnlocked: (unlocked: boolean) => void;
  setSpeechSupported: (supported: boolean) => void;
  markMessageSpoken: (messageKey: string, text: string) => void;
  clearSpokenState: (conversationId?: string) => void;
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
  pendingChatEventIds: {} as Record<string, boolean>,
  chatErrorByEventId: {} as Record<string, string | null>,
  latestChatError: null as ChatErrorPayload | null,
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

      const conversationId = get().conversationId;
      const eventId = chatWsService.send('CHAT', {
        conversation_id: conversationId,
        content: text,
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
      }));

      return true;
    },

    sendSpeechMessage: ({ audioData, audioFormat, mode }: SendSpeechMessageOptions) => {
      const trimmedAudio = audioData.trim();
      const trimmedFormat = audioFormat.trim();
      if (!trimmedAudio || !trimmedFormat) {
        return false;
      }

      const conversationId = get().conversationId;
      const eventId = chatWsService.send('SPEECH', {
        conversation_id: conversationId,
        audio_data: trimmedAudio,
        audio_format: trimmedFormat,
        mode,
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
      }));

      return true;
    },

    appendChatReply: (payload: ChatReplyPayload) => {
      set((state) => {
        if (state.chatMessages.some((message) => message.event_id === payload.event_id)) {
          return {
            pendingChatEventIds: {
              ...state.pendingChatEventIds,
              [payload.reply_to_event_id]: false,
            },
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

      set((state) => ({
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

        return {
          spokenMessages: Object.fromEntries(
            Object.entries(state.spokenMessages).filter(([key]) => !key.startsWith(`${conversationId}::`)),
          ),
          lastSpokenAt: Object.fromEntries(
            Object.entries(state.lastSpokenAt).filter(([key]) => !key.startsWith(`${conversationId}::`)),
          ),
        };
      });
    },

    resetConversation: () => {
      set((state) => ({
        conversationId: createConversationId(),
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
export const selectConversationId = (state: AiCenterState) => state.conversationId;
export const selectPendingChatEventIds = (state: AiCenterState) => state.pendingChatEventIds;
export const selectChatErrorByEventId = (state: AiCenterState) => state.chatErrorByEventId;
export const selectLatestChatError = (state: AiCenterState) => state.latestChatError;
export const selectIsChatSending = (state: AiCenterState) => state.chatMessages.some(
  (message) => message.role === 'user' && message.request_type === 'CHAT' && state.pendingChatEventIds[message.event_id],
);
export const selectSpokenMessages = (state: AiCenterState) => state.spokenMessages;
export const selectLastSpokenAt = (state: AiCenterState) => state.lastSpokenAt;
export const selectIsChatFocused = (state: AiCenterState) => state.isChatFocused;
export const selectVoiceCaptureSupported = (state: AiCenterState) => state.voiceCaptureSupported;
export const selectVoiceCaptureState = (state: AiCenterState) => state.voiceCaptureState;
export const selectVoiceCaptureError = (state: AiCenterState) => state.voiceCaptureError;
export const selectActiveVoiceEventId = (state: AiCenterState) => state.activeVoiceEventId;
export const selectActiveVoiceMode = (state: AiCenterState) => state.activeVoiceMode;

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

initializeAiCenterStoreSubscriptions();
