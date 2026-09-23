import { afterEach, vi } from "vitest";

class ResizeObserverMock implements ResizeObserver {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}

Object.defineProperty(globalThis, "ResizeObserver", {
  configurable: true,
  value: ResizeObserverMock
});

Object.defineProperty(window, "matchMedia", {
  configurable: true,
  writable: true,
  value: vi.fn().mockImplementation((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn()
  }))
});

Object.defineProperty(Element.prototype, "scrollIntoView", {
  configurable: true,
  value: vi.fn()
});

afterEach(() => {
  window.localStorage.clear();
  document.documentElement.className = "";
  delete document.documentElement.dataset.theme;
  vi.restoreAllMocks();
});
