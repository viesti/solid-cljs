import { defineConfig } from 'vitest/config'
import { playwright } from '@vitest/browser-playwright'

export default defineConfig({
  test: {
    include: [
      // relies on the config at shadow-cljs.edn, which outputs these files
      './out/vitest/vitest.*.js',
    ],
    browser: {
      provider: playwright(),
      enabled: true,
      // at least one instance is required
      instances: [
        { browser: 'chromium' },
      ],
    },

  }
})
