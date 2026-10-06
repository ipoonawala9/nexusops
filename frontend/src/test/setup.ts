import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

// Vitest runs without globals, so Testing Library's automatic cleanup is not registered.
afterEach(() => cleanup())

// jsdom gaps used by sonner (prefers-color-scheme) and Base UI popups.
if (!window.matchMedia) {
  window.matchMedia = (query: string): MediaQueryList =>
    ({
      matches: false,
      media: query,
      onchange: null,
      addListener: () => undefined,
      removeListener: () => undefined,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
      dispatchEvent: () => false,
    }) as MediaQueryList
}
if (!('ResizeObserver' in window)) {
  class ResizeObserverStub {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
  Object.assign(window, { ResizeObserver: ResizeObserverStub })
}
if (!Element.prototype.scrollIntoView) {
  Element.prototype.scrollIntoView = () => undefined
}
