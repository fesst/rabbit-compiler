import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  timeout: 180_000,
  fullyParallel: false,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: 'http://localhost:8101',
    headless: true,
    channel: 'chrome',
    launchOptions: { args: ['--no-sandbox', '--disable-dev-shm-usage'] },
    trace: 'retain-on-failure'
  },
  webServer: {
    command: 'bash ../scripts/e2e-server.sh',
    url: 'http://localhost:8101/',
    reuseExistingServer: !process.env.CI,
    timeout: 180_000
  }
});
