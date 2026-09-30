// Storefront JS unit tests (P2-T04): `npm test`.
import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    environment: 'jsdom',
    include: ['tests/**/*.test.js']
  }
});
