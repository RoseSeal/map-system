class SpeechService {
  private voice: SpeechSynthesisVoice | null = null;
  private initialized = false;

  isSupported(): boolean {
    return typeof window !== 'undefined' && 'speechSynthesis' in window && 'SpeechSynthesisUtterance' in window;
  }

  init(): boolean {
    if (!this.isSupported()) {
      return false;
    }

    if (this.initialized) {
      return true;
    }

    const loadVoices = () => {
      const voices = window.speechSynthesis.getVoices();
      this.voice = voices.find((item) => item.lang === 'zh-CN')
        || voices.find((item) => item.lang.toLowerCase().startsWith('zh'))
        || voices[0]
        || null;
    };

    loadVoices();
    window.speechSynthesis.onvoiceschanged = loadVoices;
    this.initialized = true;
    return true;
  }

  unlock(): boolean {
    if (!this.init()) {
      return false;
    }

    const silentUtterance = new SpeechSynthesisUtterance('');
    silentUtterance.volume = 0;
    window.speechSynthesis.speak(silentUtterance);
    return true;
  }

  speak(text: string, options?: { interrupt?: boolean; rate?: number; pitch?: number; volume?: number }): boolean {
    if (!this.init()) {
      return false;
    }

    const content = text.trim();
    if (!content) {
      return false;
    }

    if (options?.interrupt) {
      window.speechSynthesis.cancel();
    }

    const utterance = new SpeechSynthesisUtterance(content);
    utterance.lang = 'zh-CN';
    utterance.rate = options?.rate ?? 1;
    utterance.pitch = options?.pitch ?? 1;
    utterance.volume = options?.volume ?? 1;

    if (this.voice) {
      utterance.voice = this.voice;
    }

    window.speechSynthesis.speak(utterance);
    return true;
  }

  stop(): void {
    if (!this.isSupported()) {
      return;
    }
    window.speechSynthesis.cancel();
  }

  pause(): void {
    if (!this.isSupported()) {
      return;
    }
    window.speechSynthesis.pause();
  }

  resume(): void {
    if (!this.isSupported()) {
      return;
    }
    window.speechSynthesis.resume();
  }

  isSpeaking(): boolean {
    if (!this.isSupported()) {
      return false;
    }
    return window.speechSynthesis.speaking;
  }
}

export const speechService = new SpeechService();
