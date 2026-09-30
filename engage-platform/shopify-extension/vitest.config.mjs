// Storefront JS unit tests (P2-T04): `npm test`.
import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    environment: 'jsdom',
    // Theme blocks carry inline <script>s; let jsdom run them (wa-optin-block.test.js).
    environmentOptions: { jsdom: { runScripts: 'dangerously' } },
    include: ['tests/**/*.test.js']
  }
});
