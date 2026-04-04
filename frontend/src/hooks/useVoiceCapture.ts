import { useEffect, useRef } from 'react';
import {
  useAiCenterStore,
  selectVoiceCaptureSupported,
  selectVoiceCaptureState,
  selectVoiceCaptureError,
  selectActiveVoiceMode,
} from '../store';
import { CHAT_CONFIG } from '../config';
import { voiceRecorderService } from '../services';
import type { SpeechMode } from '../types/schema';

export function useVoiceCapture() {
  const voiceCaptureSupported = useAiCenterStore(selectVoiceCaptureSupported);
  const voiceCaptureState = useAiCenterStore(selectVoiceCaptureState);
  const voiceCaptureError = useAiCenterStore(selectVoiceCaptureError);
  const activeVoiceMode = useAiCenterStore(selectActiveVoiceMode);

  const setVoiceCaptureSupported = useAiCenterStore((state) => state.setVoiceCaptureSupported);
  const setVoiceCaptureRecording = useAiCenterStore((state) => state.setVoiceCaptureRecording);
  const setVoiceCaptureError = useAiCenterStore((state) => state.setVoiceCaptureError);
  const resetVoiceCapture = useAiCenterStore((state) => state.resetVoiceCapture);
  const sendSpeechMessage = useAiCenterStore((state) => state.sendSpeechMessage);

  const voiceSentResetTimerRef = useRef<ReturnType<typeof setTimeout>>();

  useEffect(() => {
    setVoiceCaptureSupported(voiceRecorderService.isSupported());

    return () => {
      clearTimeout(voiceSentResetTimerRef.current);
      voiceRecorderService.cancelRecording();
    };
  }, [setVoiceCaptureSupported]);

  useEffect(() => {
    if (voiceCaptureState !== 'sent') {
      clearTimeout(voiceSentResetTimerRef.current);
      return;
    }

    voiceSentResetTimerRef.current = setTimeout(() => {
      resetVoiceCapture();
    }, CHAT_CONFIG.VOICE_SENT_RESET_DELAY_MS);

    return () => {
      clearTimeout(voiceSentResetTimerRef.current);
    };
  }, [resetVoiceCapture, voiceCaptureState]);

  const handleStartVoiceRecording = async () => {
    try {
      await voiceRecorderService.startRecording();
      setVoiceCaptureRecording();
    } catch (error) {
      setVoiceCaptureError(getVoiceErrorMessage(error), null);
    }
  };

  const handleStopVoiceRecording = async (mode: SpeechMode) => {
    try {
      const { blob, audioFormat } = await voiceRecorderService.stopRecording();

      if (blob.size === 0) {
        throw new Error('未采集到有效语音，请重新录音');
      }

      if (blob.size > CHAT_CONFIG.MAX_AUDIO_SIZE_BYTES) {
        throw new Error('录音文件超过 10 MiB，请缩短录音时长');
      }

      const audioData = await voiceRecorderService.blobToBase64(blob);
      const didSend = sendSpeechMessage({ audioData, audioFormat, mode });

      if (!didSend) {
        setVoiceCaptureError('语音消息发送失败，请重新录音', null);
      }
    } catch (error) {
      setVoiceCaptureError(getVoiceErrorMessage(error), null);
    }
  };

  const cancelVoiceCapture = () => {
    voiceRecorderService.cancelRecording();
    resetVoiceCapture();
  };

  return {
    voiceCaptureSupported,
    voiceCaptureState,
    voiceCaptureError,
    activeVoiceMode,
    handleStartVoiceRecording,
    handleStopVoiceRecording,
    cancelVoiceCapture,
  };
}

function getVoiceErrorMessage(error: unknown): string {
  if (error instanceof DOMException) {
    if (error.name === 'NotAllowedError' || error.name === 'PermissionDeniedError') {
      return '麦克风权限被拒绝';
    }

    if (error.name === 'NotFoundError' || error.name === 'DevicesNotFoundError') {
      return '未检测到可用麦克风';
    }
  }

  if (error instanceof Error && error.message.trim()) {
    return error.message;
  }

  return '录音流程失败，请重新录音';
}
