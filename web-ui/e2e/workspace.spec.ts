import { test, expect } from '@playwright/test';

// Uses web-ui/e2e/fixtures/sources.zip, created by scripts/zip-sources.sh
// (zips the whole solution). Run from web-ui: npm run e2e.
const FIXTURE = 'e2e/fixtures/sources.zip';

test('upload zipped solution -> workspace over websocket -> edit, autosave, compile success in footer', async ({ page }) => {
  const pageErrors: string[] = [];
  page.on('console', (msg) => { if (msg.type() === 'error') pageErrors.push(msg.text()); });
  page.on('pageerror', (err) => pageErrors.push(String(err)));

  await page.goto('/');

  // 1. Upload the zipped solution through the top bar
  await page.setInputFiles('input[type="file"]', FIXTURE);

  // 2. The tree arrives over the websocket
  await expect(page.locator('.tree-panel .row.folder').first()).toBeVisible({ timeout: 20000 });
  const readme = page.locator('.tree-panel .row', { hasText: 'README.md' });
  await expect(readme).toBeVisible({ timeout: 20000 });
  await expect(page.locator('.footer-bar .note.success')).toContainText('files', { timeout: 20000 });

  // 3. Double-click opens a tab with the file content in Monaco
  await readme.dblclick();
  await expect(page.locator('.editor-panel .tab', { hasText: 'README.md' })).toBeVisible();
  await expect(page.locator('.monaco-editor .view-lines')).toContainText('example-rabbit', { timeout: 20000 });

  // 4. Edit: content change -> auto-save ~2s after the last keystroke
  await page.locator('.monaco-editor .view-lines').click(); // focuses the editor
  await page.keyboard.press('Control+End');
  await page.keyboard.type('E2E MARKER');
  await expect(page.locator('.monaco-editor .view-lines')).toContainText('E2E MARKER', { timeout: 5000 });
  await expect(page.locator('.footer-bar .status')).toContainText('saved README.md', { timeout: 10000 });

  // 5. Ctrl+Space opens the completion widget with server suggestions
  await page.keyboard.press('Control+Space');
  await expect(page.locator('.monaco-editor .suggest-widget')).toBeVisible({ timeout: 15000 });
  await page.keyboard.press('Escape');

  // 6. Save and compile: the local worker compiles for real -> success in the footer
  await page.getByRole('button', { name: 'Save and compile' }).click();
  await expect(page.locator('.footer-bar .note.success', { hasText: 'Compilation OK' })).toBeVisible({ timeout: 120000 });

  // 7. No uncaught frontend errors anywhere in the flow
  expect(pageErrors).toEqual([]);
});
