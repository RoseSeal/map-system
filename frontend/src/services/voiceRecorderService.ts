type RecordingResult = {
  blob: Blob;
  mimeType: string;
  audioFormat: 'webm';
};

class VoiceRecorderService {
  private mediaRecorder: MediaRecorder | null = null;
  private mediaStream: MediaStream | null = null;
  private chunks: BlobPart[] = [];
  private pendingStop: Promise<RecordingResult> | null = null;
  private readonly mimeCandidates = ['audio/webm;codecs=opus', 'audio/webm'];

  isSupported(): boolean {
    return typeof window !== 'undefined'
      && typeof navigator !== 'undefined'
      && typeof MediaRecorder !== 'undefined'
      && Boolean(navigator.mediaDevices?.getUserMedia)
      && this.getSupportedMimeType() !== null;
  }

  getPreferredMimeType(): string {
    const supportedMimeType = this.getSupportedMimeType();
    if (!supportedMimeType) {
      throw new Error('当前浏览器不支持可用的录音编码');
    }

    return supportedMimeType;
  }

  async requestPermission(): Promise<void> {
    if (!this.isSupported()) {
      throw new Error('当前浏览器不支持录音');
    }

    const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    stream.getTracks().forEach((track) => track.stop());
  }

  async startRecording(): Promise<{ mimeType: string }> {
    if (!this.isSupported()) {
      throw new Error('当前浏览器不支持录音');
    }

    if (this.mediaRecorder && this.mediaRecorder.state !== 'inactive') {
      throw new Error('录音已在进行中');
    }

    this.mediaStream = await navigator.mediaDevices.getUserMedia({ audio: true });
    const mimeType = this.getPreferredMimeType();
    this.chunks = [];
    this.mediaRecorder = new MediaRecorder(this.mediaStream, { mimeType });
    this.mediaRecorder.ondataavailable = (event: BlobEvent) => {
      if (event.data.size > 0) {
        this.chunks.push(event.data);
      }
    };
    this.mediaRecorder.start();

    return { mimeType };
  }

  stopRecording(): Promise<RecordingResult> {
    if (!this.mediaRecorder || this.mediaRecorder.state === 'inactive') {
      throw new Error('当前没有正在进行的录音');
    }

    if (this.pendingStop) {
      return this.pendingStop;
    }

    const recorder = this.mediaRecorder;
    const mimeType = recorder.mimeType || this.getPreferredMimeType();

    this.pendingStop = new Promise<RecordingResult>((resolve, reject) => {
      recorder.onstop = () => {
        try {
          const blob = new Blob(this.chunks, { type: mimeType || 'audio/webm' });
          this.cleanup();
          resolve({
            blob,
            mimeType: mimeType || 'audio/webm',
            audioFormat: 'webm',
          });
        } catch (error) {
          this.cleanup();
          reject(error instanceof Error ? error : new Error('录音数据处理失败'));
        }
      };

      recorder.onerror = () => {
        this.cleanup();
        reject(new Error('录音过程中发生错误'));
      };
    });

    recorder.stop();
    return this.pendingStop;
  }

  cancelRecording(): void {
    if (this.mediaRecorder && this.mediaRecorder.state !== 'inactive') {
      this.mediaRecorder.stop();
    }

    this.cleanup();
  }

  async blobToBase64(blob: Blob): Promise<string> {
    return await new Promise<string>((resolve, reject) => {
      const reader = new FileReader();
      reader.onloadend = () => {
        const result = reader.result;
        if (typeof result !== 'string') {
          reject(new Error('录音编码失败'));
          return;
        }

        const [, base64 = ''] = result.split(',', 2);
        if (!base64) {
          reject(new Error('录音编码失败'));
          return;
        }

        resolve(base64);
      };
      reader.onerror = () => reject(new Error('录音编码失败'));
      reader.readAsDataURL(blob);
    });
  }

  private cleanup(): void {
    if (this.mediaStream) {
      this.mediaStream.getTracks().forEach((track) => track.stop());
    }

    this.mediaRecorder = null;
    this.mediaStream = null;
    this.chunks = [];
    this.pendingStop = null;
  }

  private getSupportedMimeType(): string | null {
    if (typeof MediaRecorder === 'undefined' || typeof MediaRecorder.isTypeSupported !== 'function') {
      return null;
    }

    return this.mimeCandidates.find((candidate) => MediaRecorder.isTypeSupported(candidate)) || null;
  }
}

export const voiceRecorderService = new VoiceRecorderService();
