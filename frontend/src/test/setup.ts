import { cleanup } from '@testing-library/react';
import '@testing-library/jest-dom/vitest';
import { afterEach, vi } from 'vitest';

let randomIdCounter = 0;

// constants.ts may derive odd default SSE/WS URLs under jsdom.
// Step 1 tests mock risk/chat services, so no global URL polyfill is needed.

if (typeof globalThis.crypto === 'undefined') {
  Object.defineProperty(globalThis, 'crypto', {
    value: {
      randomUUID: () => `test-uuid-${++randomIdCounter}`,
    },
    configurable: true,
  });
}

if (typeof globalThis.crypto.randomUUID !== 'function') {
  Object.defineProperty(globalThis.crypto, 'randomUUID', {
    value: () => `test-uuid-${++randomIdCounter}`,
    configurable: true,
  });
}

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  vi.useRealTimers();
});
